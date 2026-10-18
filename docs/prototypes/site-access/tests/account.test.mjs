// Prototype state transitions, not production security verification.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from '../../../../frontend/node_modules/vite/dist/node/index.js'
import { JSDOM } from '../../../../frontend/node_modules/jsdom/lib/api.js'
import { fileURLToPath } from 'node:url'

const dom = new JSDOM('', { url: 'http://localhost/' })
globalThis.window = dom.window
globalThis.localStorage = dom.window.localStorage
globalThis.Event = dom.window.Event
const server = await createServer({ configFile: fileURLToPath(new URL('../vite.config.mjs', import.meta.url)), server: { middlewareMode: true } })
const db = await server.ssrLoadModule('/src/mock/db.js')
const account = await server.ssrLoadModule('/src/mock/account.js')
const call = (method, path, body = {}, query = {}) => account.handleAccount(method, path, body, new URLSearchParams(query)).data
const password = { password: 'PrototypeOnly42!', confirmPassword: 'PrototypeOnly42!' }
const throwsStatus = (fn, status) => assert.throws(fn, e => e.status === status)
beforeEach(() => { db.resetDb(); account.ensureAccount() })
after(async () => { await server.close(); dom.window.close() })

test('registration verifies email before account/password setup and ignores role or email injection', () => {
  const result=call('POST','/api/auth/signup-link',{email:'new@finns.test'})
  assert.equal(result.email,'new@finns.test');assert.equal(db.getDb().users.some(u=>u.email===result.email),false)
  const l=db.getDb().account.links[0]
  call('GET','/api/auth/link',{}, {purpose:'signup',token:l.id});assert.equal(l.used,false)
  throwsStatus(()=>call('POST','/api/auth/register',{...password,token:l.id,displayName:'新访客'}),403)
  call('POST','/api/auth/signup-verify',{token:l.id});assert.equal(db.getDb().users.some(u=>u.email===result.email),false)
  call('POST','/api/auth/register',{...password,token:l.id,email:'admin@finns.test',role:'ADMIN',displayName:'新访客'})
  const u=db.getDb().users.find(u=>u.email===result.email)
  assert.equal(u.role,'MEMBER');assert.equal(u.emailVerified,true);assert.equal(db.getDb().session.userId,null)
  throwsStatus(()=>call('POST','/api/auth/register',{...password,token:l.id,displayName:'重复'}),410)
  call('POST','/api/auth/login',{email:u.email,password:password.password});assert.equal(db.getDb().session.userId,u.id)
  assert.equal(JSON.stringify(db.getDb()).includes(password.password),false)
})
test('signup resend replaces old setup link; failed send preserves valid link and creates no user', () => {
  const body={email:'pending@finns.test',purpose:'signup'}
  call('POST','/api/auth/request-link',body);const first=db.getDb().account.links[0]
  call('POST','/api/auth/request-link',body);assert.equal(db.getDb().account.links.length,1)
  db.getDb().clockOffset+=db.MINUTE;call('POST','/api/auth/signup-verify',{token:first.id})
  call('POST','/api/auth/request-link',body);const latest=db.getDb().account.links[0]
  throwsStatus(()=>call('POST','/api/auth/register',{...password,token:first.id,displayName:'失效测试'}),410)
  db.getDb().clockOffset+=db.MINUTE;db.getDb().account.failSend=true;call('POST','/api/auth/request-link',body)
  assert.equal(db.getDb().account.links[0].id,latest.id)
  call('POST','/api/auth/signup-verify',{token:latest.id})
  assert.equal(db.getDb().users.some(u=>u.email===body.email),false)
  db.getDb().clockOffset+=db.DAY
  throwsStatus(()=>call('POST','/api/auth/register',{...password,token:latest.id,displayName:'过期测试'}),410)
})
test('new password boundaries are 8 through 20, with no mutation after invalid input', () => {
  db.getDb().session.userId=3
  for(const n of [7,21])throwsStatus(()=>call('POST','/api/account/password',{oldPassword:'dummy',password:'x'.repeat(n),confirmPassword:'x'.repeat(n)}),400)
  assert.equal(db.getDb().users.find(u=>u.id===3).passwordChangedAt,undefined)
  for(const n of [8,20])call('POST','/api/account/password',{oldPassword:'dummy',password:'x'.repeat(n),confirmPassword:'x'.repeat(n)})
})
test('incomplete signup email creates no message or user, pre-R2 unverified new account still cannot log in', () => {
  throwsStatus(()=>call('POST','/api/auth/signup-link',{email:'not-an-email'}),400)
  assert.equal(db.getDb().account.mail.length,0)
  db.getDb().users.push({id:9999,email:'old-new@finns.test',legacy:false,emailVerified:false,role:'MEMBER'})
  account.ensureAccount()
  throwsStatus(()=>call('POST','/api/auth/login',{email:'old-new@finns.test',password:'dummy'}),403)
})
test('resend cooldown, replacement, and failed send preserve valid previous link', () => {
  const body={email:'yuan@finns.test',purpose:'reset'}
  call('POST','/api/auth/request-link',body);const first=db.getDb().account.links[0]
  call('POST','/api/auth/request-link',body);assert.equal(db.getDb().account.links.length,1)
  db.getDb().clockOffset+=db.MINUTE;call('POST','/api/auth/request-link',body);const second=db.getDb().account.links[0]
  assert.notEqual(first.id,second.id);throwsStatus(()=>call('GET','/api/auth/link',{}, {purpose:'reset',token:first.id}),410)
  db.getDb().clockOffset+=db.MINUTE;db.getDb().account.failSend=true
  const failure=call('POST','/api/auth/request-link',body),unknown=call('POST','/api/auth/request-link',{email:'unknown@finns.test',purpose:'reset'})
  assert.deepEqual(failure,unknown);assert.equal(db.getDb().account.links.length,2)
  call('GET','/api/auth/link',{}, {purpose:'reset',token:second.id})
})
test('reset link is viewable without consuming and expires after 30 minutes', () => {
  call('POST','/api/auth/request-link',{email:'yuan@finns.test',purpose:'reset'});const l=db.getDb().account.links[0]
  call('GET','/api/auth/link',{}, {purpose:'reset',token:l.id});call('GET','/api/auth/link',{}, {purpose:'reset',token:l.id});assert.equal(l.used,false)
  db.getDb().clockOffset+=31*db.MINUTE
  throwsStatus(()=>call('POST','/api/auth/reset',{...password,token:l.id}),410)
})
test('legacy recovery verifies mailbox and resets only token owner, signs all devices out', () => {
  db.getDb().session.userId=3;call('POST','/api/auth/request-link',{email:'yuan@finns.test',purpose:'reset'})
  const l=db.getDb().account.links[0],u=db.getDb().users.find(u=>u.id===3)
  assert.equal(u.emailVerified,false);call('POST','/api/auth/reset',{...password,token:l.id,userId:1})
  assert.equal(u.emailVerified,true);assert.ok(u.passwordChangedAt);assert.equal(db.getDb().users[0].passwordChangedAt,undefined)
  assert.equal(db.getDb().session.userId,null);assert.ok(db.getDb().account.devices[3].every(d=>!d.active))
  throwsStatus(()=>call('POST','/api/auth/reset',{...password,token:l.id}),410)
  assert.ok(db.getDb().account.mail.some(m=>m.title.includes('已更新')))
  assert.equal(JSON.stringify(db.getDb()).includes(password.password),false)
})
test('wrong old password and save failure do not mutate credentials or session', () => {
  db.getDb().session.userId=3;db.getDb().account.wrongOld=true
  throwsStatus(()=>call('POST','/api/account/password',{...password,oldPassword:'dummy-old'}),400)
  db.getDb().account.wrongOld=false;db.getDb().account.failSave=true
  throwsStatus(()=>call('POST','/api/account/password',{...password,oldPassword:'dummy-old'}),503)
  assert.equal(db.getDb().users.find(u=>u.id===3).passwordChangedAt,undefined)
  assert.equal(db.getDb().session.userId,3);assert.ok(db.getDb().account.devices[3].every(d=>d.active))
})
test('active password change retains current session, revokes recovery and tolerates notification failure', () => {
  db.getDb().session.userId=3;call('POST','/api/auth/request-link',{email:'yuan@finns.test',purpose:'reset'})
  const l=db.getDb().account.links[0];db.getDb().account.failNotice=true
  const result=call('POST','/api/account/password',{...password,oldPassword:'dummy-old'})
  assert.equal(result.noticeFailed,true);assert.equal(db.getDb().session.userId,3)
  assert.equal(db.getDb().account.devices[3].filter(d=>d.active).length,1)
  assert.equal(db.getDb().account.devices[3].find(d=>d.current).active,true)
  throwsStatus(()=>call('GET','/api/auth/link',{}, {purpose:'reset',token:l.id}),410)
})
test('verification expires at 24 hours, legacy standalone verification retains login', () => {
  db.getDb().session.userId=3;call('POST','/api/auth/request-link',{email:'yuan@finns.test',purpose:'verify'})
  const l=db.getDb().account.links[0];call('POST','/api/auth/verify',{token:l.id});assert.equal(db.getDb().session.userId,3)
  db.getDb().clockOffset+=db.MINUTE;call('POST','/api/auth/request-link',{email:'yuan@finns.test',purpose:'verify'})
  const next=db.getDb().account.links[0];db.getDb().clockOffset+=db.DAY
  throwsStatus(()=>call('POST','/api/auth/verify',{token:next.id}),410)
})

test('profile changes bind to current session, reject switched account, ignore protected fields and preserve state on failure', () => {
  db.getDb().session.userId=3
  const before=call('GET','/api/me/profile'),admin=db.getDb().users.find(u=>u.id===1),adminName=admin.displayName
  db.getDb().account.failProfile=true
  throwsStatus(()=>call('PATCH','/api/me/profile',{expectedUserId:3,displayName:'演示修改',bio:'模拟介绍'}),503)
  assert.equal(call('GET','/api/me/profile').displayName,before.displayName)
  db.getDb().session.userId=1
  throwsStatus(()=>call('PATCH','/api/me/profile',{expectedUserId:3,displayName:'错误归属',bio:''}),403)
  assert.equal(admin.displayName,adminName)
  db.getDb().session.userId=3
  const updated=call('PATCH','/api/me/profile',{expectedUserId:3,userId:1,email:'fake@finns.test',role:'ADMIN',displayName:'演示修改',bio:'模拟介绍'})
  assert.equal(updated.email,before.email);assert.equal(updated.bio,'模拟介绍');assert.equal(db.getDb().users.find(u=>u.id===3).role,'MEMBER')
  db.getDb().session.userId=null
  throwsStatus(()=>call('GET','/api/me/profile'),401)
  throwsStatus(()=>call('PATCH','/api/me/profile',{expectedUserId:3,displayName:'越权'}),401)
})

test('signed-in recovery targets current email, needs no old password, respects cooldown and keeps session until reset', () => {
  throwsStatus(()=>call('POST','/api/account/password-reset-link',{}),401)
  db.getDb().session.userId=3
  call('POST','/api/account/password-reset-link',{email:'admin@finns.test',userId:1})
  const a=db.getDb().account,l=a.links[0]
  assert.equal(l.userId,3);assert.equal(a.mail[0].email,'yuan@finns.test');assert.equal(db.getDb().session.userId,3)
  assert.equal(db.getDb().users.find(u=>u.id===3).passwordChangedAt,undefined)
  assert.ok(call('GET','/api/account/security').resetCooldown>0)
  call('POST','/api/account/password-reset-link',{});assert.equal(a.links.length,1)
  db.getDb().clockOffset+=db.MINUTE
  assert.equal(call('GET','/api/account/security').resetCooldown,0)
  a.failSend=true;call('POST','/api/account/password-reset-link',{});assert.equal(a.links[0].id,l.id)
  call('GET','/api/auth/link',{}, {purpose:'reset',token:l.id});assert.equal(db.getDb().session.userId,3)
  call('POST','/api/auth/reset',{...password,token:l.id})
  assert.equal(db.getDb().session.userId,null);assert.ok(a.devices[3].every(d=>!d.active))
})
