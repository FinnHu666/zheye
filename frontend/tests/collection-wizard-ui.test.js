import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

const dom=new JSDOM('<div id="app"></div>',{url:'http://localhost/',pretendToBeVisual:true})
for(const key of ['window','document','Document','Element','HTMLElement','SVGElement','Node','Event','MouseEvent','history','location','localStorage'])globalThis[key]=dom.window[key]
globalThis.requestAnimationFrame=dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame=dom.window.cancelAnimationFrame.bind(dom.window)
window.matchMedia=()=>({matches:false,addEventListener(){},removeEventListener(){}})
window.confirm=()=>true
window.HTMLElement.prototype.scrollIntoView=()=>{}
await build({configFile:false,plugins:[vue()],logLevel:'silent',build:{outDir:resolve('../target/collection-wizard-ui-tests'),emptyOutDir:true,lib:{entry:resolve('tests/collection-wizard-ui-entry.js'),formats:['es'],fileName:()=> 'entry.mjs'}}})
const {createApp,nextTick,h,createRouter,createMemoryHistory,RouterView,CollectionWizardPage,currentUser}=await import(new URL('../../target/collection-wizard-ui-tests/entry.mjs',import.meta.url))
const flush=async()=>{for(let i=0;i<10;i++){await new Promise(r=>setTimeout(r,0));await nextTick()}}
const draft=()=>({id:81,revision:3,name:'已保存专题',description:'简介',audience:'开发者',goal:'技能提升',format:'CUSTOM',status:'DRAFT',visibility:'PUBLIC',metadata:{tags:['服务端标签'],category:'engineering',seoTitle:'服务器SEO',audiences:['开发者'],sectionLayouts:['LIST']},sections:[{title:'第一章',description:'',entries:[{kind:'NOTE',title:'正文',body:'内容'}]}]})
const response=(body,status=200)=>({ok:status<400,status,json:async()=>body})
const mount=async(step,handler)=>{
 currentUser.value={id:1,permissions:['collection.manage']};document.body.innerHTML='<div id="app"></div>';globalThis.fetch=handler
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/me/collections/:id/compose/:step',component:CollectionWizardPage},{path:'/me/collections/import',component:{render:()=>h('p','追加导入入口 '+router.currentRoute.value.query.append)}}]});await router.push(`/me/collections/81/compose/${step}`)
 const app=createApp({render:()=>h(RouterView)});app.use(router);app.mount('#app');await flush();return app
}
const api=(writes,fail=false)=>(url,options={})=>{
 if(url.includes('csrf'))return response({})
 if(url==='/api/auth/me')return response({id:1,email:'test@finns.dev'})
 if(url.includes('capabilities'))return response({schedulingEnabled:false,layoutsEnabled:false})
 if(url.includes('review/mine/content'))return response({items:{}})
 if(url.includes('/activity'))return response([])
 if(url.includes('/api/me/posts'))return response({items:[],hasMore:false})
 if(options.method){writes.push({url,...options});return response(fail?{message:'版本冲突，输入已保留'}:draft(),fail?409:200)}
 return response(draft())
}
test('hydration uses server metadata and does not save before an edit',async()=>{
 const writes=[];const app=await mount('basic',api(writes));try{
 assert.match(document.body.textContent,/服务端标签/);assert.equal(document.querySelector('#cw-category').value,'开发与工程');assert.equal(writes.length,0)
 }finally{app.unmount()}
})
test('saving SEO sends metadata and keeps the input when persistence fails',async()=>{
 const writes=[];const app=await mount('publish',api(writes,true));try{
 const input=[...document.querySelectorAll('input')].find(e=>e.maxLength===60);input.value='更新后的SEO';input.dispatchEvent(new window.Event('input',{bubbles:true}));await flush()
 const save=[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='保存草稿');save.click();await flush()
 assert.equal(writes.length,1);assert.equal(JSON.parse(writes[0].body).metadata.seoTitle,'更新后的SEO');assert.equal(input.value,'更新后的SEO');assert.match(document.body.textContent,/版本冲突/)
 }finally{app.unmount()}
})
test('a failed save blocks publication and disabled scheduling cannot be selected',async()=>{
 const writes=[];const app=await mount('publish',api(writes,true));try{
 const input=[...document.querySelectorAll('input')].find(e=>e.maxLength===60);input.value='不能丢失';input.dispatchEvent(new window.Event('input',{bubbles:true}));await flush()
 const publish=[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='发布专题');publish.click();await flush()
 assert.equal(writes.filter(w=>w.url.endsWith('/publish')).length,0);assert.ok(document.querySelector('input[name="cw-when"][disabled]'));assert.equal(input.value,'不能丢失')
 }finally{app.unmount()}
})
test('chapter management and moving a module are persisted through the draft API',async()=>{
 const writes=[];const app=await mount('content',api(writes));try{
 const input=document.querySelector('.cwb-chapter-settings input');input.value='章节改名';input.dispatchEvent(new window.Event('input',{bubbles:true}));await flush()
 ;[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='添加章节').click();await flush()
 assert.equal(document.querySelectorAll('.cwb-chapter-settings input').length,2)
 ;[...document.querySelectorAll('.cwb-mod')].find(e=>e.textContent.trim().endsWith('富文本')).click();await flush()
 const select=[...document.querySelectorAll('select')].find(e=>e.parentElement.textContent.startsWith('所属章节'));select.value='0';select.dispatchEvent(new window.Event('change',{bubbles:true}));await flush()
 ;[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='保存草稿').click();await flush()
 const saved=JSON.parse(writes.at(-1).body);assert.equal(saved.sections[0].title,'章节改名');assert.equal(saved.sections.length,2);assert.equal(saved.sections[0].entries.length,2);assert.equal(saved.sections[1].entries.length,0)
 }finally{app.unmount()}
})
test('switching accounts blocks saves and preserves the original input',async()=>{
 const writes=[],normal=api(writes);const app=await mount('publish',(url,options)=>url==='/api/auth/me'?response({id:2,email:'other@finns.dev'}):normal(url,options));try{
 const input=[...document.querySelectorAll('input')].find(e=>e.maxLength===60);input.value='原账号私人内容';input.dispatchEvent(new window.Event('input',{bubbles:true}));await flush()
 ;[...document.querySelectorAll('button')].find(e=>e.textContent.trim()==='保存草稿').click();await flush()
 assert.equal(writes.length,0);assert.equal(input.value,'原账号私人内容');assert.match(document.body.textContent,/另一个账号|原账号/)
 }finally{app.unmount()}
})
test('newly selected article references are rendered in the shared preview',async()=>{
 const writes=[],normal=api(writes);const d=draft();d.sections[0].entries=[{kind:'POST',postId:9,title:'',annotation:''}]
 const app=await mount('publish',(url,options)=>url.includes('/api/me/posts')?response({items:[{id:9,title:'文章引用预览',summary:'已发布摘要',authorName:'作者'}],hasMore:false}):url.endsWith('/draft')&&!options?.method?response(d):normal(url,options));try{
 assert.match(document.body.textContent,/文章引用预览/);assert.match(document.body.textContent,/已发布摘要/);assert.doesNotMatch(document.querySelector('.collection-reader').textContent,/原文已不可用/)
 }finally{app.unmount()}
})
