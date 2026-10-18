// Explicit real-provider acceptance: fixed non-sensitive sources, at most six AI requests, no retry.
import { mkdir, writeFile } from 'node:fs/promises'
import { signupViaDevOutbox } from './lib/dev-signup.mjs'
const base=new URL(process.argv[2]||'http://127.0.0.1:8082')
if(base.hostname!=='127.0.0.1'||base.protocol!=='http:'||base.port==='8080')throw Error('Only the isolated loopback application is allowed')
const cookies=new Map(),report={date:new Date().toISOString(),base:base.origin,status:'not-run',attemptedRequests:0,cases:[]}
async function call(path,body,method='POST'){
  if(body)await call('/api/auth/csrf')
  const response=await fetch(new URL(path,base),{method:body?method:'GET',headers:{'Content-Type':'application/json',Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),...(body?{'X-XSRF-TOKEN':decodeURIComponent(cookies.get('XSRF-TOKEN')||'')}:{})},...(body?{body:JSON.stringify(body)}:{})})
  for(const cookie of response.headers.getSetCookie()){const pair=cookie.split(';')[0],i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1))}
  const value=await response.json().catch(()=>({}));if(!response.ok)throw Error(value.message||'HTTP '+response.status);return value
}
const empty={topic:'',audience:'',purpose:'',goal:'',style:'guide',density:'comfortable',styleNote:'',source:'',constraints:'',context:''}
const cases=[
  {topic:'大学生短租准备',audience:'第一次实习租房的大学生',purpose:'避坑解决问题',style:'cards',source:'作者案例：先记录预算、租期和通勤要求。看房时记录采光、噪音与需要确认的问题。入住前核对交接事项并保留记录。本资料仅为经验整理案例，不提供法律判断。'},
  {topic:'虚构作品观看路线',audience:'第一次观看该系列的人',purpose:'看番路线',style:'timeline',source:'虚构案例：先看作品甲，再看作品乙。作品丙为作者尚未确定顺序的支线，标为待补充。作品名称仅作占位，不增加真实上映年份、人物或其他作品。'},
  {topic:'文字工具第一次实践',audience:'工具新手',purpose:'学习某项工具',style:'docs',source:'作者示例：打开文字工具，新建一个空文档，输入“练习”，保存到自己的练习目录，再次打开检查文字。工具名称、版本和具体按钮由作者后续补充。'}
]
try{
  if(process.env.FINNS_AI_TEST_EMAIL&&process.env.FINNS_AI_TEST_PASSWORD){
    await call('/api/auth/login',{email:process.env.FINNS_AI_TEST_EMAIL,password:process.env.FINNS_AI_TEST_PASSWORD})
  }else{
    await signupViaDevOutbox((path,body)=>call(path,body),{email:'real-ai-'+crypto.randomUUID()+'@finns.dev',password:'Ai-'+crypto.randomUUID().slice(0,12),displayName:'真实AI隔离验收'}) // 需 FINNS_MAIL_MODE=outbox
  }
  const capabilities=await call('/api/me/topic-builder/capabilities')
  if(!capabilities.aiAvailable||/fixture|mock|test/i.test(capabilities.model))throw Error('真实 DeepSeek 未配置；固定模型测试不能代替真实验收')
  report.model=capabilities.model
  for(const sample of cases){
    const record={topic:sample.topic,source:sample.source};report.cases.push(record)
    report.attemptedRequests++;const chat=await call('/api/me/topic-builder/chat',{form:empty,messages:[{role:'user',text:`创建${sample.topic}，给${sample.audience}，用途${sample.purpose}。`} ]})
    record.chat={model:chat.model,elapsedMs:chat.elapsedMs,usage:chat.usage,reply:chat.reply,form:chat.form}
    report.attemptedRequests++;const generated=await call('/api/me/topic-builder/generate',{form:{...empty,...sample,goal:'优先忠实整理原资料，未知信息明确标为待补充'}})
    record.generate={model:generated.model,elapsedMs:generated.elapsedMs,usage:generated.usage,document:generated.document,warnings:generated.warnings}
    const submitted=generated.document,idempotencyKey=crypto.randomUUID()
    const saved=await call('/api/me/topic-builder/drafts',{idempotencyKey,document:submitted})
    const retry=await call('/api/me/topic-builder/drafts',{idempotencyKey,document:submitted})
    if(retry.id!==saved.id)throw Error('Idempotent create returned another draft')
    const edited=structuredClone(saved.document);edited.blocks[0].title+='（已核对）'
    const updated=await call('/api/me/collections/'+saved.id+'/builder-document',{revision:saved.revision,document:edited},'PUT')
    const reopened=await call('/api/me/collections/'+saved.id+'/builder-document')
    if(JSON.stringify(reopened.document)!==JSON.stringify(edited)||reopened.revision!==updated.revision)throw Error('Saved real-model document did not recover exactly')
    record.draft={id:saved.id,revision:reopened.revision,blockCount:reopened.document.blocks.length,idempotentRetry:true,editAndReopen:true}
  }
  report.status='api-passed-quality-review-required'
}catch(error){report.status=report.attemptedRequests?'failed':'not-run';report.reason=error.message;process.exitCode=1}
await mkdir(new URL('../target/',import.meta.url),{recursive:true})
await writeFile(new URL('../target/topic-builder-real-ai.json',import.meta.url),JSON.stringify(report,null,2))
console.log(JSON.stringify({status:report.status,attemptedRequests:report.attemptedRequests,reason:report.reason||'人工核对生成质量后才能完成验收'}))
