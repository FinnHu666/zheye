// Browser-mock behavior evidence only (FINNS-17 R3 / requirements revision 6); does not validate real server security or concurrency.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from '../../../../frontend/node_modules/vite/dist/node/index.js'
import { JSDOM } from '../../../../frontend/node_modules/jsdom/lib/api.js'
import { fileURLToPath } from 'node:url'
const dom = new JSDOM('', { url: 'http://localhost/' }); dom.window.fetch = globalThis.fetch
for (const key of ['window','document','location','localStorage','Event','CustomEvent']) globalThis[key] = key === 'window' ? dom.window : dom.window[key]
const server = await createServer({ configFile:fileURLToPath(new URL('../vite.config.mjs',import.meta.url)), server:{ middlewareMode:true, hmr:false } })
const db=await server.ssrLoadModule('/src/mock/db.js'), account=await server.ssrLoadModule('/src/mock/account.js'), ua=await server.ssrLoadModule('/src/mock/user-admin.js'), rv=await server.ssrLoadModule('/src/mock/review.js'), mock=await server.ssrLoadModule('/src/mock/server.js')
const reading=await server.ssrLoadModule('/src/lib/review-reading.js')
const mutate=fn=>{fn(db.getDb());db.saveDb()}
const as=id=>mutate(()=>ua.simulateSignIn(id))
const doc=id=>db.getDb().review.documents.find(d=>d.id===id)
async function request(method,path,body,csrf=true){const response=await window.fetch(path,{method,headers:csrf?{'X-XSRF-TOKEN':db.getDb().csrf}:{},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,body:await response.json()}}
let serial=0
const submit=(id,key='request-'+(++serial))=>request('POST',`/api/review/mine/${id}/submit`,{expectedVersion:doc(id).draft.version,requestId:key})
const save=(id,body)=>request('PUT',`/api/review/mine/${id}`,{...doc(id).draft,...body,expectedVersion:doc(id).draft.version})
const pending=id=>db.getDb().review.tasks.find(t=>t.docId===id&&t.status==='PENDING')
const decide=(task,action='APPROVE',note='')=>request('POST','/api/review/tasks/'+task.id,{action,note,expectedGeneration:task.generation})
const titles=flow=>flow.events.map(e=>e.title)
const stages=flow=>flow.events.map(e=>e.stage)
beforeEach(()=>{db.resetDb();account.ensureAccount();ua.simulateSignIn(1);mock.installMockServer();db.saveDb()})
after(async()=>{await server.close();dom.window.close()})

test('default independent permission belongs only to owner, admin and anonymous direct calls fail',async()=>{
  assert.ok(db.getDb().permissions.OWNER.includes('content.review'));assert.ok(!db.getDb().permissions.ADMIN.includes('content.review'))
  as(2);assert.equal((await request('GET','/api/review/queue')).status,403)
  as(null);assert.equal((await request('GET','/api/review/queue')).status,401)
})

test('clean article, comment and a first collection publication all follow one pipeline and publish directly',async()=>{
  as(3);const count=db.getDb().review.tasks.length
  const article=await submit(1703);assert.equal(article.body.outcome,'PUBLISHED')
  const created=await request('POST','/api/review/mine',{type:'COMMENT'});await save(created.body.id,{title:'一条评论',body:'感谢分享'});assert.equal((await submit(created.body.id)).body.outcome,'PUBLISHED')
  assert.equal(doc(1708).approvedOnce,false);assert.equal((await submit(1708)).body.outcome,'PUBLISHED','collection first publication no longer needs approval')
  assert.equal(db.getDb().review.tasks.length,count)
  for(const id of [1703,1708]){const flow=doc(id).flows[0];assert.deepEqual(stages(flow),['SUBMIT','CHECK','ROUTE','RESULT']);assert.equal(flow.status,'PUBLISHED')}
})

test('low sensitivity is not blocked: published at once and recorded for the owner to look at later',async()=>{
  as(3);await save(1703,{body:'这里有一句演示低敏词'});const result=await submit(1703)
  assert.equal(result.body.outcome,'PUBLISHED');assert.equal(result.body.level,'LOW');assert.equal(pending(1703),undefined);assert.ok(doc(1703).published)
  assert.ok(titles(doc(1703).flows[0]).includes('放行并留痕'))
  as(1);const noted=(await request('GET','/api/review/noted')).body.items;assert.ok(noted.some(n=>n.docId===1703&&n.words.includes('演示低敏词')));assert.ok(noted.some(n=>n.docId===1710))
  as(2);assert.equal((await request('GET','/api/review/noted')).status,403)
})

test('medium sensitivity goes to review on the first hit: no blocking rounds, idempotent retry, resubmission supersedes',async()=>{
  as(3);await save(1703,{body:'演示拦截词'});const key='stable-first'
  const first=await submit(1703,key);assert.equal(first.body.outcome,'PENDING');assert.equal(doc(1703).published,null);const task=pending(1703);assert.equal(task.kind,'REVIEW');assert.equal(task.level,'MEDIUM')
  assert.deepEqual((await submit(1703,key)).body,first.body);assert.equal(db.getDb().review.tasks.filter(t=>t.docId===1703).length,1)
  assert.deepEqual(stages(doc(1703).flows[0]),['SUBMIT','CHECK','ROUTE']);assert.equal(doc(1703).flows[0].status,'IN_REVIEW')
  await save(1703,{body:'演示拦截词，改了一版'});await submit(1703);assert.equal(db.getDb().review.tasks.find(t=>t.id===task.id).status,'INVALID');assert.equal(db.getDb().review.tasks.filter(t=>t.docId===1703&&t.status==='PENDING').length,1)
  assert.equal(doc(1703).flows.at(-1).status,'INVALID')
})

test('high sensitivity is automatically rejected, never public, and a manual recheck task is created',async()=>{
  as(3);await save(1703,{body:'这是演示高敏词的内容'});const result=await submit(1703)
  assert.equal(result.body.outcome,'AUTO_REJECTED');assert.equal(doc(1703).published,null);const task=pending(1703);assert.equal(task.kind,'RECHECK');assert.equal(task.level,'HIGH')
  assert.equal(doc(1703).flows[0].status,'AUTO_REJECTED');assert.ok(titles(doc(1703).flows[0]).includes('自动不通过'))
  as(null);assert.ok(!(await request('GET','/api/review/public')).body.items.some(x=>x.id===1703))
})

test('highest level wins, and punctuation / width variants cannot bypass a rule',async()=>{
  as(3);await save(1703,{body:'演示拦截词 和 演.示.高.敏.词'});assert.equal((await submit(1703)).body.outcome,'AUTO_REJECTED')
  await save(1708,{body:'演　示，拦·截 词'});assert.equal((await submit(1708)).body.outcome,'PENDING')
  assert.equal(pending(1708).level,'MEDIUM');assert.equal(rv.normalize('演.示 ＡＢＣ…'),'演示abc')
})

test('the author never sees matched words for high sensitivity nor reviewer names; reviewers see both',async()=>{
  as(3);const mine=(await request('GET','/api/review/mine')).body.items.find(i=>i.id===1709)
  const authorText=JSON.stringify(mine.flows);assert.ok(!authorText.includes('演示高敏词'));assert.ok(mine.flows[0].events.every(e=>e.detail===''))
  assert.ok(mine.flows[0].events.some(e=>e.title==='未通过'))
  const mediumMine=(await request('GET','/api/review/mine')).body.items.find(i=>i.id===1702);assert.ok(JSON.stringify(mediumMine.flows).includes('演示拦截词'),'medium hits remain visible to the author')
  as(1);const task=pending(1709),detail=(await request('GET','/api/review/tasks/'+task.id)).body
  assert.deepEqual(detail.matches,['演示高敏词']);assert.ok(detail.flow.events.some(e=>e.detail.includes('演示高敏词')))
  const upheld=await decide(task,'UPHOLD','确认属于高敏感内容');assert.equal(upheld.status,200)
  as(3);const after=(await request('GET','/api/review/mine')).body.items.find(i=>i.id===1709).flows[0]
  assert.ok(after.events.every(e=>e.actor===''||e.actor==='审核员'));assert.ok(after.events.some(e=>e.actor==='审核员'))
})

test('recheck decisions: reason is mandatory both ways, only recheck actions are valid, overturn publishes the inspected snapshot',async()=>{
  const task=pending(1709);assert.equal((await decide(task,'APPROVE','x')).status,400)
  assert.equal((await decide(task,'UPHOLD','  ')).status,400);assert.equal((await decide(task,'OVERTURN','')).status,400)
  const snapshot=structuredClone(task.snapshot);as(3);await save(1709,{body:'作者之后改写的内容'});as(1)
  assert.equal((await decide(task,'OVERTURN','属于误判，放行')).status,200);assert.deepEqual(doc(1709).published,snapshot)
  assert.equal(doc(1709).flows[0].status,'PUBLISHED');assert.deepEqual(stages(doc(1709).flows[0]).slice(-2),['REVIEW','RESULT']);assert.equal((await decide(task,'UPHOLD','重复')).status,409)
  assert.equal(db.getDb().review.tasks.find(t=>t.id===task.id).status,'OVERTURNED')
})

test('upholding keeps content private; the author can resubmit and the old recheck task is invalidated',async()=>{
  const task=structuredClone(pending(1709));assert.equal((await decide(task,'UPHOLD','维持原判')).status,200);assert.equal(doc(1709).published,null);assert.equal(doc(1709).flows[0].status,'UPHELD')
  as(3);await save(1709,{body:'改成了普通内容'});assert.equal((await submit(1709)).body.outcome,'PUBLISHED');assert.equal(doc(1709).flows[0].status,'PUBLISHED')
})

test('saving a newer draft never changes the pending snapshot; approve publishes only the inspected version',async()=>{
  const snapshot=structuredClone(pending(1701).snapshot);as(3);await save(1701,{body:'新工作稿，不是已提交的内容'});as(1)
  assert.equal((await decide(pending(1701))).status,200);assert.deepEqual(doc(1701).published,snapshot);assert.notEqual(doc(1701).draft.body,snapshot.body);assert.equal(doc(1701).flows[0].status,'PUBLISHED')
})

test('owner cannot self approve, no other reviewer is reported and content remains private',async()=>{
  const task=pending(1705);assert.equal((await decide(task)).status,403);assert.equal(doc(1705).published,null)
  const item=(await request('GET','/api/review/queue')).body.items.find(t=>t.docId===1705);assert.equal(item.self,true);assert.equal(item.otherReviewerAvailable,false)
})

test('explicit demo reviewer can approve owner content, has neither user management nor rule access',async()=>{
  mutate(d=>{d.users.find(u=>u.id===3).accountStatus='DISABLED';rv.loadReviewScene()});assert.equal(db.getDb().users.find(u=>u.id===3).accountStatus,'ACTIVE');as(4);assert.equal((await decide(pending(1705))).status,200)
  assert.equal((await request('GET','/api/admin/users')).status,403);assert.equal((await request('GET','/api/review/rules')).status,403);assert.equal((await request('GET','/api/review/rules/import-preview')).status,403)
  mutate(d=>d.permissions.REVIEW_DEMO=[]);assert.equal((await request('GET','/api/review/queue')).status,403)
})

test('stopped author invalidates pending work and the flow, published version stays visible, restore does not resurrect the task',async()=>{
  const task=structuredClone(pending(1702)),old=structuredClone(doc(1702).published)
  mutate(d=>{d.users.find(u=>u.id===3).accountStatus='DISABLED'});assert.equal((await decide(task)).status,409);assert.deepEqual(doc(1702).published,old)
  assert.equal(doc(1702).flows[0].status,'INVALID');assert.ok(titles(doc(1702).flows[0]).includes('提交已失效'))
  mutate(d=>{d.users.find(u=>u.id===3).accountStatus='ACTIVE'});assert.equal((await decide(task)).status,409)
  as(null);assert.ok((await request('GET','/api/review/public')).body.items.some(x=>x.id===1702))
})

test('withdraw, re-submit and deleted comment invalidate stale review; duplicate approve cannot republish',async()=>{
  const old=structuredClone(pending(1701));as(3);await request('POST','/api/review/mine/1701/withdraw',{});as(1);assert.equal((await decide(old)).status,409)
  as(3);await submit(1701);const fresh=structuredClone(pending(1701));as(1);assert.equal((await decide(fresh)).status,200);assert.equal((await decide(fresh)).status,409)
  const comment=structuredClone(pending(1704));as(5);await request('POST','/api/review/mine/1704/delete',{});as(1);assert.equal((await decide(comment)).status,409)
})

test('pending, auto-rejected and down content never appears in public results, old version remains readable',async()=>{
  as(null);const result=(await request('GET','/api/review/public')).body
  assert.deepEqual(result.items.map(x=>x.id).sort(),[1702,1706,1710]);assert.equal(result.items.find(x=>x.id===1702).version,1)
  assert.equal((await request('GET','/api/review/public?q='+encodeURIComponent('站长的公开写作手册'))).body.total,0)
  assert.equal((await request('GET','/api/review/public?q='+encodeURIComponent('高敏感命中'))).body.total,0)
})

test('detector error and missing active rules block publishing without creating a flow or changing the old publication',async()=>{
  as(3);const before=structuredClone(doc(1702).published),flows=doc(1702).flows.length
  mutate(d=>d.review.settings.detectFailure=true);assert.equal((await submit(1702)).status,503)
  mutate(d=>{d.review.settings.detectFailure=false;d.review.rules.forEach(r=>r.enabled=false)});assert.equal((await submit(1702)).status,503)
  assert.deepEqual(doc(1702).published,before);assert.equal(doc(1702).flows.length,flows)
})

test('reject reason, write failure and stale generation preserve state and old publication',async()=>{
  const task=structuredClone(pending(1702)),before=structuredClone(doc(1702).published)
  assert.equal((await decide(task,'REJECT','  ')).status,400)
  mutate(d=>d.review.settings.failWriteOnce=true);assert.equal((await decide(task)).status,503);assert.equal(pending(1702).status,'PENDING');assert.deepEqual(doc(1702).published,before)
  mutate(d=>d.review.settings.conflictOnce=true);assert.equal((await decide(task)).status,409)
  assert.equal((await decide(task,'REJECT','不符合发布要求')).status,200);assert.equal(doc(1702).flows[0].status,'REJECTED');assert.deepEqual(doc(1702).published,before)
})

test('queue metrics and filters expose the two review levels and today\'s handled count',async()=>{
  const queue=(await request('GET','/api/review/queue')).body
  assert.equal(queue.medium,4);assert.equal(queue.high,1);assert.equal(queue.pending,5);assert.equal(queue.low,1)
  assert.equal((await request('GET','/api/review/queue?level=HIGH')).body.total,1);assert.equal((await request('GET','/api/review/queue?level=MEDIUM&type=COMMENT')).body.total,1)
  await request('POST','/api/review/tasks/'+pending(1701).id,{action:'REJECT',note:'请补充来源',expectedGeneration:pending(1701).generation})
  assert.equal((await request('GET','/api/review/queue')).body.doneToday,1);assert.equal((await request('GET','/api/review/queue?status=DONE')).body.total,1)
})

test('rules validate levels, duplicates after normalization and punctuation-only phrases; owner-only maintenance',async()=>{
  const before=structuredClone(doc(1706).published)
  assert.equal((await request('POST','/api/review/rules',{phrase:'  ',enabled:true})).status,400)
  assert.equal((await request('POST','/api/review/rules',{phrase:'…… ！！',enabled:true})).status,400)
  assert.equal((await request('POST','/api/review/rules',{phrase:'演示 - 拦截词',enabled:true,level:'HIGH'})).status,400)
  assert.equal((await request('POST','/api/review/rules',{phrase:'历史发布',note:'演示规则',enabled:true,level:'HIGH'})).status,200)
  assert.equal((await request('POST','/api/review/rules',{phrase:'默认等级',enabled:true})).status,200)
  const rules=(await request('GET','/api/review/rules?q='+encodeURIComponent('默认等级'))).body.items;assert.equal(rules[0].level,'MEDIUM')
  assert.deepEqual(doc(1706).published,before);assert.equal(doc(1706).down,false)
  as(2);assert.equal((await request('GET','/api/review/rules')).status,403)
})

test('changing a rule level changes the next submission only, never historical publications',async()=>{
  const old=structuredClone(doc(1710).published);const rule=(await request('GET','/api/review/rules?q='+encodeURIComponent('演示低敏词'))).body.items[0]
  assert.equal((await request('POST','/api/review/rules',{...rule,level:'HIGH',expectedVersion:rule.version})).status,200)
  assert.deepEqual(doc(1710).published,old);as(3);await save(1703,{body:'演示低敏词'});assert.equal((await submit(1703)).body.outcome,'AUTO_REJECTED')
})

test('rule list reports enabled counts per level and supports the level filter, search, status and pagination',async()=>{
  let result=(await request('GET','/api/review/rules')).body
  assert.deepEqual(result.levels,{HIGH:1,MEDIUM:2,LOW:1});assert.equal((await request('GET','/api/review/rules?level=HIGH')).body.total,1)
  for(let n=0;n<9;n++)await request('POST','/api/review/rules',{phrase:'分页演示'+n,enabled:true})
  result=(await request('GET','/api/review/rules?q='+encodeURIComponent('分页')+'&page=1')).body;assert.equal(result.total,9);assert.equal(result.items.length,1);assert.equal(result.size,8)
  assert.equal((await request('GET','/api/review/rules?status=DISABLED')).body.total,1)
})

test('rule edits keep identity, stale versions fail, deletion removes matching and keeps audit and snapshots',async()=>{
  await request('POST','/api/review/rules',{phrase:'CRUD演示词',note:'查找备注',enabled:true,level:'MEDIUM'})
  const rule=(await request('GET','/api/review/rules?q='+encodeURIComponent('查找备注'))).body.items[0]
  assert.equal((await request('POST','/api/review/rules',{...rule,phrase:'CRUD修改词',enabled:false,expectedVersion:rule.version})).status,200)
  assert.equal((await request('POST','/api/review/rules',{...rule,expectedVersion:rule.version})).status,409)
  const task=structuredClone(pending(1702));assert.equal((await request('DELETE','/api/review/rules/1',{expectedVersion:99})).status,409)
  assert.equal((await request('DELETE','/api/review/rules/1',{expectedVersion:1})).status,200)
  assert.equal(db.getDb().review.ruleHistory[0].action,'删除规则');assert.deepEqual(pending(1702),task)
  as(3);await save(1703,{body:'演示拦截词'});assert.equal((await submit(1703)).body.outcome,'PUBLISHED','deleted rule no longer matches')
  as(1);assert.equal((await request('DELETE','/api/review/rules/1',{expectedVersion:2})).status,404)
})

test('import is grouped by tag with suggested levels; suspects excluded by default; batch enable per tag; duplicates skipped',async()=>{
  const preview=(await request('GET','/api/review/rules/import-preview')).body
  assert.equal(preview.source.mock,true);assert.ok(preview.source.license.includes('Apache-2.0'));assert.ok(preview.items.find(i=>i.phrase==='习').suspect);assert.ok(preview.items.find(i=>i.phrase==='天安门').suspect)
  const levels=Object.fromEntries(preview.groups.map(g=>[g.tag,g.level]));assert.deepEqual(levels,{引流:'MEDIUM',广告:'LOW',诈骗:'MEDIUM',政治:'HIGH'})
  assert.ok(preview.groups.find(g=>g.tag==='政治').suspect>=4)
  const before=(await request('GET','/api/review/rules')).body.enabledCount
  const first=(await request('POST','/api/review/rules/import',{groups:[{tag:'诈骗',level:'MEDIUM',enabled:false},{tag:'政治',level:'HIGH',enabled:false}]})).body
  assert.ok(first.added>=5);assert.equal(first.suspectSkipped,4)
  const rules=(await request('GET','/api/review/rules?tag='+encodeURIComponent('政治'))).body;assert.ok(rules.items.every(r=>r.level==='HIGH'&&r.enabled===false&&r.tag==='政治'));assert.ok(!rules.items.some(r=>r.phrase==='天安门'))
  assert.equal((await request('GET','/api/review/rules')).body.enabledCount,before)
  assert.equal((await request('POST','/api/review/rules/import',{groups:[{tag:'诈骗',level:'MEDIUM',enabled:false}]})).body.added,0)
  as(3);await save(1703,{body:'这里有刷单信息'});assert.equal((await submit(1703)).body.outcome,'PUBLISHED','imported rules stay inactive until enabled')
  as(1);const batch=(await request('POST','/api/review/rules/batch',{tag:'诈骗',enabled:true}));assert.equal(batch.status,200);assert.equal((await request('GET','/api/review/rules')).body.tags.find(t=>t.tag==='诈骗').enabled,3)
  as(3);await save(1703,{body:'这里有刷单信息，再试一次'});assert.equal((await submit(1703)).body.outcome,'PENDING')
  as(1);const withSuspect=(await request('POST','/api/review/rules/import',{groups:[{tag:'政治',level:'MEDIUM',enabled:false}],includeSuspect:true})).body;assert.equal(withSuspect.added,4)
  assert.equal((await request('POST','/api/review/rules/batch',{tag:'不存在',enabled:true})).status,404)
  as(2);assert.equal((await request('POST','/api/review/rules/import',{groups:[]})).status,403);assert.equal((await request('POST','/api/review/rules/batch',{tag:'诈骗',enabled:false})).status,403)
})

test('low-sensitivity records can be inspected, marked as read and traced to the published content; reviewers only',async()=>{
  const items=(await request('GET','/api/review/noted')).body.items;const item=items.find(n=>n.docId===1710)
  assert.ok(item.excerpt.includes('演示低敏词'));assert.equal(item.published,true);assert.equal(item.reviewed,undefined)
  assert.equal((await request('POST','/api/review/noted',{docId:1710,action:'REVIEWED'})).status,200)
  assert.equal((await request('GET','/api/review/noted')).body.items.find(n=>n.docId===1710).reviewed,true)
  assert.equal((await request('GET','/api/review/publications/1710')).status,200)
  assert.equal((await request('POST','/api/review/publications/1710',{action:'TAKE_DOWN',note:'复查后下架',expectedVersion:1,expectedDown:false})).status,200)
  assert.equal((await request('GET','/api/review/noted')).body.items.find(n=>n.docId===1710).published,false)
  as(2);assert.equal((await request('POST','/api/review/noted',{docId:1710,action:'REVIEWED'})).status,403)
})

test('down/restore require reason and specified version, own restore is prohibited and saving cannot lift down',async()=>{
  const body={action:'RESTORE',note:'复核原版本后恢复',expectedVersion:1,expectedDown:true}
  assert.equal((await request('POST','/api/review/publications/1707',{...body,note:''})).status,400)
  assert.equal((await request('POST','/api/review/publications/1707',body)).status,200);assert.equal(doc(1707).down,false)
  assert.equal((await request('POST','/api/review/publications/1707',body)).status,409)
  await request('POST','/api/review/publications/1707',{action:'TAKE_DOWN',note:'单独处置',expectedVersion:1,expectedDown:false})
  as(5);assert.equal((await save(1707,{body:'新的普通文字'})).status,200);assert.equal((await submit(1707)).status,409)
  mutate(d=>{d.permissions.MEMBER.push('content.review')});assert.equal((await request('POST','/api/review/publications/1707',body)).status,403)
})

test('CSRF and cross-account checks reject writes without exposing another author\'s content',async()=>{
  const task=pending(1701);assert.equal((await request('POST','/api/review/tasks/'+task.id,{action:'APPROVE',expectedGeneration:task.generation},false)).status,403)
  as(2);assert.equal((await save(1703,{body:'越权修改'})).status,404);assert.ok(!(await request('GET','/api/review/mine')).body.items.some(i=>i.id===1703))
})

test('detail returns the complete long submission with its flow, newer drafts stay separate, unknown ids are 404',async()=>{
  const task=pending(1701),body=task.snapshot.body;assert.ok(body.length>1500)
  as(3);await save(1701,{body:'仅修改工作稿'});as(1)
  const detail=(await request('GET','/api/review/tasks/'+task.id)).body
  assert.equal(detail.snapshot.body,body);assert.equal(detail.published,null);assert.deepEqual(stages(detail.flow),['SUBMIT','CHECK','ROUTE']);assert.equal(reading.readingBlocks(body).filter(b=>b.kind==='heading').length,7)
  assert.equal((await request('GET','/api/review/tasks/9999999')).status,404)
  as(2);assert.equal((await request('GET','/api/review/tasks/'+task.id)).status,403)
  as(null);assert.equal((await request('GET','/api/review/publications/1702')).status,401)
  as(1);const update=(await request('GET','/api/review/tasks/'+pending(1702).id)).body;assert.equal(update.snapshot.version,2);assert.equal(update.published.version,1);assert.notEqual(update.snapshot.body,update.published.body)
})

test('reading preserves complete paragraphs, headings, lists, quotation, code and literal unsafe text',()=>{
  const text='# 标题\n\n第一段\n继续第一段\n\n- 要点\n\n> 引用\n\n```html\n<script>literal</script>\n```\n\n最后一段'
  const blocks=reading.readingBlocks(text)
  assert.deepEqual(blocks.map(b=>b.kind),['heading','paragraph','list','quote','code','paragraph'])
  assert.equal(blocks[1].text,'第一段\n继续第一段');assert.equal(blocks[4].text,'<script>literal</script>');assert.equal(blocks[5].text,'最后一段')
  const source='<img onerror=literal>演示词 & ABC abc',segments=reading.markedSegments(source,['演示词','abc','ab'])
  assert.equal(segments.map(p=>p.text).join(''),source);assert.deepEqual(segments.filter(p=>p.hit).map(p=>p.text),['演示词','ABC','abc'])
  assert.equal(reading.readingBlocks('```\n未闭合代码')[0].text,'未闭合代码')
})
