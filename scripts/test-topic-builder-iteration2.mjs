// Isolated acceptance only. Never print the credential or persist it in reports.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { signupViaDevOutbox } from './lib/dev-signup.mjs'
const base = 'http://127.0.0.1:8082', reportPath = new URL('../target/iteration2-acceptance.json', import.meta.url)
const email = 'builder-20261009@finns.dev', password = 'builder-test-2026'
// Cookie client with recursive CSRF refresh, keeping tokens out of output.
function session() {
  const cookies=new Map()
  const call=async(path,method='GET',body,expected=200,csrf=true)=>{
    if(method!=='GET'&&csrf&&!cookies.has('XSRF-TOKEN'))await call('/api/auth/csrf','GET',undefined,204)
    const headers={'Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; ')}
    if(method!=='GET'&&csrf&&cookies.has('XSRF-TOKEN'))headers['X-XSRF-TOKEN']=decodeURIComponent(cookies.get('XSRF-TOKEN'))
    const res=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)})
    for(const line of res.headers.getSetCookie()){const [pair]=line.split(';'),eq=pair.indexOf('=');if(pair.slice(eq+1))cookies.set(pair.slice(0,eq),pair.slice(eq+1));else cookies.delete(pair.slice(0,eq))}
    const value=await res.json().catch(()=>null)
    assert.equal(res.status,expected,`${method} ${path}: ${value?.message || res.status}`)
    return value
  }
  return call
}
const a=session();await a('/api/auth/login','POST',{email,password})
if(process.argv.includes('--verify-restart')) {
  const report=JSON.parse(await readFile(reportPath,'utf8'))
  assert.equal((await a('/api/me/ai-settings/deepseek')).configured,true)
  assert.equal((await a('/api/me/topic-builder/capabilities')).aiAvailable,true)
  const stored=await a(`/api/me/collections/${report.copyId}/builder-document`)
  assert.deepEqual(stored.document.reviewNotes,report.expectedReviewNotes ?? ['作者已核验：仅为固定测试资料。'])
  assert.equal((await a('/api/me/topic-builder/module-favorites')).items.length,report.favoriteCount)
  report.restartVerified=true;report.acceptanceCompleted=true;report.restartAt=new Date().toISOString();await writeFile(reportPath,JSON.stringify(report,null,2));console.log('Restart: personal configuration, favorites and edited draft restored.');process.exit(0)
}
try { const previous=JSON.parse(await readFile(reportPath,'utf8')); if(previous.calls?.length) throw new Error('Real AI budget already used; run --verify-restart only.') } catch(e) { if(e.code !== 'ENOENT') throw e }
const key=process.env.DEEPSEEK_API_KEY
assert.ok(key,'Set the existing user credential in the process environment without printing it.')
const b=session(), suffix=Date.now(), bEmail=`iteration2-${suffix}@finns.dev`
await signupViaDevOutbox((path,body)=>b(path,'POST',body,path==='/api/auth/register'?201:200),{email:bEmail,password,displayName:'第二轮隔离账号'}) // 需 FINNS_MAIL_MODE=outbox
assert.equal((await b('/api/me/ai-settings/deepseek')).configured,false)
await a('/api/me/ai-settings/deepseek','PUT',{},403,false)
const configured=await a('/api/me/ai-settings/deepseek','PUT',{key});assert.ok(configured.configured);assert.ok(!JSON.stringify(configured).includes(key))
assert.equal((await b('/api/me/ai-settings/deepseek')).configured,false)
const form={topic:'短租准备记录',audience:'初次整理资料的学生',purpose:'经验整理',goal:'整理自己的准备清单',style:'guide',density:'comfortable',styleNote:'',source:'固定非敏感案例：整理者在看房前记录预算、租期和通勤要求。看房时将问题记在清单中。入住前保存交接记录。本案例不包含法律结论。',constraints:'仅组织这些资料，缺失内容标待补充。',context:'第二轮验收'}
await b('/api/me/topic-builder/chat','POST',{form,messages:[{role:'user',text:'整理创建问卷'}]},503)
await b('/api/me/topic-builder/generate','POST',{form},503)
assert.equal((await b('/api/me/topic-builder/generate','POST',{form:{...form,source:''}})).model,'structure-only')
const report={date:new Date().toISOString(),calls:[],sourceId:null,copyId:null,restartVerified:false}
async function paid(user,path,payload,expected=200) {
  assert.ok(report.calls.length<3,'Three-request limit')
  report.calls.push({path,startedAt:new Date().toISOString()});await writeFile(reportPath,JSON.stringify(report,null,2))
  const start=Date.now(),result=await user(path,'POST',payload,expected)
  Object.assign(report.calls.at(-1),{status:expected,elapsedMs:Date.now()-start,model:result?.model,usage:result?.usage,error:expected===200?undefined:result?.message})
  await writeFile(reportPath,JSON.stringify(report,null,2));return result
}
await paid(a,'/api/me/topic-builder/chat',{form,messages:[{role:'user',text:'按已有资料整理短租准备专题，面向学生，保留我已填写的答案。'}]})
const generated=await paid(a,'/api/me/topic-builder/generate',{form})
const doc=generated.document
doc.form.topic='第二轮模块复用验收';doc.confirmedForm.topic=doc.form.topic
doc.blocks=['text','steps','question','html'].map(kind=>({id:crypto.randomUUID(),kind,title:{text:'经验记录',steps:'准备步骤',question:'常见问题',html:'时间轴'}[kind],body:kind==='steps'?'记录需求\n整理清单\n保存资料':'固定案例资料，可独立修改。',...(kind==='html'?{code:{rendererVersion:1,source:'<style>ol{border-left:3px solid #71966b;padding-left:28px}li{padding:12px;color:#245d49}</style><ol><li>准备：记录需求</li><li>实践：整理清单</li><li>回顾：保存资料</li></ol>',frameHeight:360}}:{})}))
doc.reviewNotes=['作者已核验：仅为固定测试资料。'];doc.layoutMode='canvas';doc.blocks.forEach((block,i)=>block.layout={x:i%2?52:3,y:20+Math.floor(i/2)*400,w:45,h:360})
const source=await a('/api/me/topic-builder/drafts','POST',{idempotencyKey:crypto.randomUUID(),document:doc},201);report.sourceId=source.id
for(const block of doc.blocks)await a('/api/me/topic-builder/module-favorites','POST',{block})
const favorites=await a('/api/me/topic-builder/module-favorites');report.favoriteCount=favorites.items.length
assert.equal((await b('/api/me/topic-builder/module-favorites')).items.length,0)
await b(`/api/me/topic-builder/module-favorites/${favorites.items[0].id}`,'DELETE',undefined,404)
await a('/api/me/topic-builder/module-favorites','POST',{block:doc.blocks[0]},403,false)
const copy=structuredClone(doc);copy.form.topic='第二轮独立编辑验收';copy.blocks.forEach(block=>block.id=crypto.randomUUID());copy.blocks[0].body='复制后的独立修改'
const saved=await a('/api/me/topic-builder/drafts','POST',{idempotencyKey:crypto.randomUUID(),document:copy},201);report.copyId=saved.id
assert.notEqual(saved.document.blocks[0].id,source.document.blocks[0].id)
assert.equal((await a(`/api/me/collections/${source.id}/builder-document`)).document.blocks[0].body,'固定案例资料，可独立修改。')
await b(`/api/me/collections/${saved.id}/builder-document`,'GET',undefined,403)
await a(`/api/me/collections/${saved.id}/builder-document`,'PUT',{revision:99,document:copy},409)
await b('/api/me/ai-settings/deepseek','PUT',{key:'invalid-iteration2-test-key'})
await paid(b,'/api/me/topic-builder/generate',{form},502)
await b('/api/me/ai-settings/deepseek','DELETE')
assert.equal((await b('/api/me/topic-builder/capabilities')).aiAvailable,false)
await b('/api/me/topic-builder/generate','POST',{form},503)
await a('/api/auth/logout','POST',undefined,204)
await a('/api/auth/login','POST',{email,password});assert.equal((await a('/api/me/ai-settings/deepseek')).configured,true)
await writeFile(reportPath,JSON.stringify(report,null,2))
console.log(JSON.stringify({passed:true,realRequests:report.calls.length,sourceId:report.sourceId,copyId:report.copyId,favoriteCount:report.favoriteCount}))
