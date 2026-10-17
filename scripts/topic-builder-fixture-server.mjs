// Manual UI acceptance only. Local deterministic fixtures do not constitute real AI acceptance.
import http from 'node:http'
const server=http.createServer(async(req,res)=>{
  if(req.url!=='/chat/completions'||req.method!=='POST'){res.writeHead(404).end();return}
  let input='';for await(const chunk of req){input+=chunk;if(input.length>200000){res.writeHead(413).end();return}}
  try{
    const request=JSON.parse(input),chat=request.max_tokens===2048,text=request.messages.filter(m=>m.role==='user').map(m=>m.content).join('\n')
    let form
    if(chat){
      const anime=/动画|看番/.test(text),tool=/工具|学习/.test(text)
      form={topic:anime?'新手动画观看路线':tool?'工具入门实践指南':'大学生实习短租指南',audience:anime?'第一次接触这个系列的观众':tool?'第一次使用这项工具的新手':'第一次外地实习的大学生',purpose:anime?'看番路线':tool?'学习某项工具':'避坑解决问题',goal:'根据作者资料整理一条清晰路径',context:'固定非敏感测试资料'}
    } else form=JSON.parse(request.messages.at(-1).content)
    const value=chat?{reply:'这是隔离环境的固定测试回复，请在问卷确认主题、人群和资料。',form}:{blocks:[{title:'背景与目标',kind:'text',body:form.goal||'待补充目标',origin:'source'},{title:form.purpose==='看番路线'?'观看顺序':form.purpose==='学习某项工具'?'第一次实践':'租房准备',kind:'steps',body:form.source.replace(/#{1,6}\s*/g,'').slice(0,3500),origin:'source'},{title:'需要确认的问题',kind:'question',body:'待补充：结合实际情况核对自己的记录。',origin:'source'}],warnings:['隔离环境固定模型数据，仅用于验证交互和保存，未调用 DeepSeek。']}
    res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify(value)}}],usage:{prompt_tokens:100,completion_tokens:200,total_tokens:300}}))
  }catch{res.writeHead(400).end()}
})
server.listen(55440,'127.0.0.1',()=>console.log('Fixed model fixtures: 127.0.0.1:55440 (not real AI)'))
