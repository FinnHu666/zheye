import { createApp, nextTick } from 'vue'
import { HtmlCodeDialog, HtmlBlockFrame, compileHtmlBlock } from './html-blocks.js'
import './styles.css'

const storageKey = 'finns-req014-prototype-v1'
const clone = value => JSON.parse(JSON.stringify(value))
const uid = () => crypto.randomUUID()
const styles = [
  { id: 'guide', name: '清晰指南', description: '浅色背景、单栏阅读、明显章节层级，适中留白，突出行动步骤。', suitable: '适合：经验分享、操作指南' },
  { id: 'cards', name: '问题卡片', description: '内容按卡片分组，突出问题标题和行动建议，方便快速定位。', suitable: '适合：避坑、问题速查' },
  { id: 'timeline', name: '路线时间线', description: '用编号与连接线组织节点，突出先后顺序，沿路线逐步阅读。', suitable: '适合：看番路线、学习路径' },
  { id: 'docs', name: '学习文档', description: '目录与标题层级清晰，内容紧凑、装饰较少，适合反复查阅。', suitable: '适合：工具学习、参考文档' }
]
const examples = [
  { id: 'rent', icon: '⌂', short: '大学生短租', hint: '整理经验，少踩坑', topic: '大学生实习短租指南', audience: '第一次外地实习的大学生', purpose: '避坑解决问题', goal: '了解短租需要准备的事情，建立自己的找房与看房判断顺序。', style: 'guide', context: '中国大陆城市实习，租期约 1—6 个月', source: '# 我的短租经验\n\n## 先明确自己的需求\n我先整理了租期、预算、通勤和是否合租的要求，并记录哪些条件可以协商。\n\n## 找房与看房准备\n整理想问的问题。\n记录看房观察与房源说明。\n把各个选项放在一起比较。\n\n## 信息不一致怎么办？\n把不清楚的信息列成问题，再向相关方确认，并保留自己的记录。以上仅为案例演示资料，需作者结合真实情境核验。' },
  { id: 'anime', icon: '▷', short: '看番路线', hint: '安排顺序，串起故事', topic: '新手动画观看路线', audience: '第一次接触这个系列的观众', purpose: '看番路线', goal: '了解建议观看次序，按自己的时间安排一次完整观看。', style: 'timeline', context: '作品名称、上映年份与分支顺序由作者补充', source: '# 我的观看路线\n\n## 从这里开始\n先认识主要角色和故事背景。本段由作者补充具体作品，不默认生成不存在的片名。\n\n## 接着看什么\n主线第一部分。\n主线第二部分。\n根据兴趣选择番外。\n\n## 番外一定要看吗？\n由作者解释番外与主线的关系，并注明自己的推荐依据。' },
  { id: 'tool', icon: '⌘', short: '学习一项工具', hint: '从入门到第一次完成', topic: '工具入门实践路线', audience: '没有使用经验的工具新手', purpose: '学习某项工具', goal: '完成准备、第一次操作和一个小练习，知道如何检查结果。', style: 'docs', context: '具体工具名称与版本由作者补充', source: '# 工具入门\n\n## 先准备什么\n说明适用版本、已有基础和本次练习材料。\n\n## 完成第一次操作\n打开工具并确认环境。\n按照作者提供的真实操作步骤完成练习。\n记录结果并核对预期。\n\n## 遇到失败怎样检查？\n记录错误信息，检查前置条件，再查阅对应版本的官方资料。' }
]
const blankForm = () => ({ topic:'', audience:'', purpose:'', goal:'', style:'guide', density:'comfortable', styleNote:'', source:'', constraints:'', context:'' })

createApp({
  components:{HtmlCodeDialog,HtmlBlockFrame},
  data() { return { styles, examples, purposes:[{label:'避坑解决问题',icon:'◇',description:'识别问题，找到行动办法'},{label:'看番路线',icon:'▷',description:'安排作品与观看次序'},{label:'学习某项工具',icon:'⌘',description:'准备、实践与结果检查'},{label:'资源整理',icon:'▤',description:'把资料组织成可查阅目录'}], questionnaireSteps:['主题与人群','页面风格','资料与要求','需求摘要'], form:blankForm(), generatedForm:blankForm(), chatInput:'', conversation:[], activeExample:'rent', questionnaireOpen:false, questionStep:0, formError:'', fileError:'', view:'chat', blocks:[], selectedBlock:null, removedBlock:null, previewMode:'edit', previewDevice:'desktop', generationStage:0, generationRun:0, simulateFailure:false, generationInput:null, generationBrief:blankForm(), briefRecovered:false, candidate:null, pendingNavigation:null, generatedWithoutSource:false, drafts:[], currentId:null, savedFingerprint:'', savedNotice:false, storageError:'', toast:'', timers:[], focusOrigin:null, previousView:'chat', dragState:null, scrollDragFrame:0, layoutMode:'flow', narrowCanvas:innerWidth<=760, codeDialogOpen:false, codeEditId:null, codeInitialSource:'', codeInitialTitle:'', codeInitialHeight:360 } },
  computed: {
    canvasStacked() { return this.narrowCanvas||this.previewDevice==='mobile' },
    canvasHeight() { return Math.max(360,...this.blocks.map((block,index)=>{const layout=this.canvasLayout(block,index);return layout.y+layout.h+28})) },
    draggedBlock() { return this.blocks.find(block=>block.id===this.dragState?.id) },
    selectedStyle() { return this.styleFor(this.form.style) },
    audienceSuggestions() { return ['初次尝试的新手','有一定经验的读者','不限定人群'] },
    activeBlock() { return this.blocks.find(block => block.id===this.selectedBlock) },
    fingerprint() { return JSON.stringify({form:this.generatedForm,brief:this.generationBrief,blocks:this.blocks,layoutMode:this.layoutMode}) },
    isDirty() { return this.fingerprint !== this.savedFingerprint },
    hasUnsavedWork() { return Boolean(this.generatedForm.topic && this.isDirty) },
    pendingRequirements() { return JSON.stringify(this.form)!==JSON.stringify(this.generatedForm) }
  },
  watch: {
    layoutMode(value) { this.cancelBlockDrag();if(value==='canvas')this.blocks.forEach((block,index)=>{if(!block.layout)block.layout=this.defaultCanvasLayout(index)}) },
    previewDevice() { this.cancelBlockDrag() },
    view() { this.cancelBlockDrag() },
    previewMode() { this.cancelBlockDrag() },
    questionStep() { nextTick(()=>{ if(this.$refs.dialog)this.$refs.dialog.scrollTop=0 }) },
    questionnaireOpen(value) { this.cancelBlockDrag();document.body.style.overflow=value?'hidden':'' },
    pendingNavigation(value) { document.body.style.overflow=value?'hidden':'';if(value)nextTick(()=>document.querySelector('.confirm-dialog button')?.focus()) },
    'generatedForm.style'(value) { if(this.view==='workbench'&&!this.questionnaireOpen)this.form.style=value },
    'generatedForm.topic'(value) { if(this.view==='workbench'&&!this.questionnaireOpen)this.form.topic=value },
    'generatedForm.density'(value) { if(this.view==='workbench'&&!this.questionnaireOpen)this.form.density=value }
  },
  mounted() {
    try { const saved=JSON.parse(localStorage.getItem(storageKey)||'[]'); this.drafts=Array.isArray(saved)?saved.filter(d=>d.version===1&&d.form&&Array.isArray(d.blocks)):[] } catch { this.storageError='演示草稿无法读取；可以继续体验，原有存储不会被自动清除。' }
    this.routeFromHash()
    window.addEventListener('hashchange', this.routeFromHash)
    window.addEventListener('keydown', this.handleDialogKeys)
    window.addEventListener('keydown', this.handleDragEscape)
    window.addEventListener('resize', this.updateNarrowCanvas)
    window.addEventListener('beforeunload', this.warnUnsaved)
  },
  beforeUnmount() { this.cancelBlockDrag();this.timers.forEach(clearTimeout); window.removeEventListener('hashchange',this.routeFromHash); window.removeEventListener('keydown',this.handleDialogKeys);window.removeEventListener('keydown',this.handleDragEscape);window.removeEventListener('resize',this.updateNarrowCanvas);window.removeEventListener('beforeunload',this.warnUnsaved);document.body.style.overflow='' },
  methods: {
    openHtmlCode(block=null) {
      if(!block&&this.blocks.filter(item=>item.kind==='html').length>=20){this.notify('原型最多添加 20 个 HTML 模块。');return}
      this.codeEditId=block?.id||null;this.codeInitialSource=block?.code?.source||'';this.codeInitialTitle=block?.title||'';this.codeInitialHeight=block?.code?.frameHeight||360;this.codeDialogOpen=true
    },
    setHtmlFrameHeight(event) { const height=Number(event.target.value);if(!this.activeBlock?.code)return;this.activeBlock.code.frameHeight=Number.isFinite(height)?Math.round(Math.max(120,Math.min(1200,height))):360;event.target.value=this.activeBlock.code.frameHeight },
    commitHtmlCode(payload) {
      try { compileHtmlBlock(payload.code.source) } catch(error) { this.notify(error.message);return }
      let block=this.codeEditId?this.blocks.find(item=>item.id===this.codeEditId):null
      if(this.codeEditId&&!block){this.notify('原模块已不存在，请取消后重新导入。');return}
      if(block){block.title=payload.title;block.code=clone(payload.code)}
      else {block={id:uid(),kind:'html',title:payload.title,body:'',code:clone(payload.code)};if(this.layoutMode==='canvas')block.layout={x:3,y:this.canvasHeight,w:90,h:Math.min(1600,payload.code.frameHeight+140)};this.blocks.push(block)}
      this.selectedBlock=block.id;this.previewMode='edit';this.codeDialogOpen=false;this.savedNotice=false;nextTick(()=>this.scrollToBlock(block.id));this.notify(this.codeEditId?'模块代码已更新，原位置与尺寸保留。':'HTML 模块已添加，可以继续移动和调整大小。')
    },
    styleFor(id) { return styles.find(s=>s.id===id)||styles[0] },
    lines(body) { return body.split('\n').map(s=>s.trim()).filter(Boolean) },
    notify(text) { this.toast=text; this.timers.push(setTimeout(()=>{this.toast=''},2600)) },
    chooseExample(id) { const example=examples.find(e=>e.id===id); this.activeExample=id; this.chatInput=`我想创建「${example.topic}」，主要给${example.audience}看，用途是${example.purpose}，页面希望是${this.styleFor(example.style).name}。` },
    sendMessage() {
      const text=this.chatInput.trim(); if(!text)return
      const example=examples.find(e=>text.includes(e.topic))||(/番|动画/.test(text)?examples[1]:/工具|学习/.test(text)?examples[2]:/租房|短租/.test(text)?examples[0]:null)
      if(!this.conversation.length) {
        if(example) { this.activeExample=example.id; this.form={...blankForm(),topic:example.topic,audience:example.audience,purpose:example.purpose,goal:example.goal,style:example.style,context:example.context} }
        else { this.activeExample=null;this.form={...blankForm(),topic:text.slice(0,80)} }
      }
      for(const [label,key] of [['主题','topic'],['人群','audience'],['用途','purpose'],['目标','goal'],['要求','constraints']]){const match=text.match(new RegExp(label+'[：:]\\s*([^；;\\n]+)'));if(match)this.form[key]=match[1].trim().slice(0,key==='topic'||key==='purpose'?80:key==='audience'?160:key==='goal'?300:500)}
      const explicitStyle=styles.find(style=>text.includes('风格：'+style.name)||text.includes('风格:'+style.name));if(explicitStyle)this.form.style=explicitStyle.id
      this.conversation.push({role:'user',text},{role:'assistant',text:'这是模拟沟通：案例会预填示例答案，也支持“主题：…；人群：…；用途：…”这样的明确输入。已有答案会保留，请在问卷中核对、补充和确认。',action:true}); this.chatInput=''
      this.openQuestionnaire()
    },
    openQuestionnaire(step=0) { this.focusOrigin=document.activeElement; this.previousView=this.view; this.questionStep=step; this.formError='';this.questionnaireOpen=true; nextTick(()=>{document.querySelector('.questionnaire .close-button')?.focus()}) },
    closeQuestionnaire() { this.questionnaireOpen=false;this.formError='';nextTick(()=>this.focusOrigin?.focus()) },
    handleDialogKeys(event) {
      if((!this.questionnaireOpen&&!this.pendingNavigation)||event.key!=='Tab')return
      const selector=this.pendingNavigation?'.confirm-dialog button':'.questionnaire button:not([disabled]),.questionnaire input:not([type=file]),.questionnaire select,.questionnaire textarea'
      const all=[...document.querySelectorAll(selector)].filter(e=>e.getClientRects().length)
      if(!all.length)return
      const first=all[0],last=all[all.length-1]
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}
    },
    validateBasics() {
      const missing=!this.form.topic.trim()?'专题主题':!this.form.audience.trim()?'目标人群':!this.form.purpose.trim()?'主要用途':''
      if(missing){this.formError=`请先确认${missing}。`;this.questionStep=0;nextTick(()=>document.getElementById(missing==='专题主题'?'topic-field':missing==='目标人群'?'audience-field':'purpose-field')?.focus());return false}
      this.formError='';return true
    },
    jumpStep(step) { if(step>0&&!this.validateBasics())return;this.questionStep=step;this.formError='' },
    nextStep() { if(!this.validateBasics())return;if(this.questionStep<3){this.questionStep++;return}this.startGeneration() },
    fillExampleSource() { const example=examples.find(e=>e.id===this.activeExample);if(example)this.form.source=example.source;this.fileError='' },
    async readSourceFile(event) {
      const file=event.target.files?.[0];if(!file)return
      try { if(!/\.(txt|md|markdown)$/i.test(file.name))throw new Error('请选择 TXT 或 Markdown 文件。');if(file.size>1048576)throw new Error('文件最多 1 MiB。');const text=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());if(text.length>16000)throw new Error('原型最多接收 16,000 字，请分批体验。');this.form.source=text;this.fileError='' } catch(error) { this.fileError=error instanceof TypeError?'无法读取，请使用 UTF-8 文字文件。':error.message } finally { event.target.value='' }
    },
    startGeneration(retry=false) {
      if(!this.validateBasics())return
      if(!retry)this.generationInput=clone(this.form)
      this.candidate=null
      this.questionnaireOpen=false;this.savedNotice=false;this.view='generating';this.generationStage=0
      const run=++this.generationRun
      for(let stage=1;stage<=3;stage++)this.timers.push(setTimeout(()=>{if(run===this.generationRun)this.generationStage=stage},stage*350))
      this.timers.push(setTimeout(()=>{if(run!==this.generationRun)return;if(this.simulateFailure){this.view='generation-error';return}this.finishGeneration()},1450))
    },
    cancelGeneration() { this.generationRun++;this.view=this.generatedForm.topic?'workbench':'chat';this.openQuestionnaire(3) },
    retryGeneration() { this.simulateFailure=false;this.startGeneration(true) },
    parseSource(source) {
      const groups=[];let title='资料内容',body=[]
      const flush=()=>{if(body.join('\n').trim())groups.push({title,body:body.join('\n').trim()});body=[]}
      for(const line of source.replace(/\r\n?/g,'\n').split('\n')){if(/^#{1,6}\s+/.test(line)){flush();title=line.replace(/^#{1,6}\s+/,'').trim()}else body.push(line)}flush()
      return groups.length?groups:[{title:'资料内容',body:source.trim()}]
    },
    finishGeneration() {
      const f=clone(this.generationInput)
      const content=f.source.trim()?this.parseSource(f.source):[{title:'背景与目标',body:'待补充：说明这个专题适用的背景及要解决的问题。'},{title:f.purpose==='看番路线'?'建议观看顺序':f.purpose==='学习某项工具'?'第一次实践':'建议行动顺序',body:'待补充：第一步\n待补充：第二步\n待补充：结果检查'},{title:'常见问题',body:'待补充：你希望回答的具体问题与资料依据。'}]
      const blocks=content.map((item,index)=>({id:uid(),title:item.title,body:item.body,kind:/怎么办|怎样|问题|一定|失败|吗[？?]?$/.test(item.title)?'question':/准备|操作|顺序|接着|实践|路线/.test(item.title)&&index>0?'steps':'text'}))
      this.candidate={form:f,blocks,withoutSource:!f.source.trim()}
      if(this.generatedForm.topic){this.view='generation-review';window.scrollTo(0,0)}else this.acceptCandidate()
    },
    acceptCandidate() {
      if(!this.candidate)return
      const result=this.candidate;this.generatedForm=clone(result.form);this.generationBrief=clone(result.form);this.briefRecovered=false;this.form=clone(result.form);this.blocks=clone(result.blocks);this.generatedWithoutSource=result.withoutSource;this.selectedBlock=this.blocks[0]?.id;this.layoutMode='flow';this.previewMode='edit';this.view='workbench';this.removedBlock=null;this.savedNotice=false;this.candidate=null;location.hash=this.currentId?`draft/${this.currentId}`:'workbench'
    },
    keepOriginal() { this.candidate=null;this.view='workbench';this.notify('已保留原稿。新创建要求仍在问卷中，可继续调整。') },
    selectBlock(id) { this.selectedBlock=id },
    scrollToBlock(id) { document.getElementById(`block-${id}`)?.scrollIntoView({behavior:'smooth',block:'center'}) },
    moveBlock(index,direction) { const target=index+direction;if(target<0||target>=this.blocks.length)return;[this.blocks[index],this.blocks[target]]=[this.blocks[target],this.blocks[index]] },
    beginBlockDrag(event,id,surface) {
      if(event.button!==0||(surface==='preview'&&this.previewMode!=='edit'))return
      this.cancelBlockDrag();event.preventDefault();event.currentTarget.focus();this.selectedBlock=id
      this.dragState={type:'sort',id,surface,pointerId:event.pointerId,startX:event.clientX,startY:event.clientY,x:event.clientX,y:event.clientY,active:false,targetId:null,after:false,axis:'vertical',handle:event.currentTarget}
      event.currentTarget.setPointerCapture(event.pointerId)
    },
    updateBlockDrag(event) {
      const drag=this.dragState;if(!drag||event.pointerId!==drag.pointerId)return
      drag.x=event.clientX;drag.y=event.clientY
      if(drag.type==='canvas'){event.preventDefault();this.updateCanvasTransform();return}
      if(!drag.active&&Math.hypot(drag.x-drag.startX,drag.y-drag.startY)>6){drag.active=true;this.scrollDragFrame=requestAnimationFrame(this.scrollWhileDragging)}
      if(drag.active){event.preventDefault();this.updateDropTarget()}
    },
    updateDropTarget() {
      const drag=this.dragState;if(!drag?.active)return
      const selector=drag.surface==='preview'?'.topic-block[data-block-id]':'.structure-item[data-block-id]'
      const target=document.elementFromPoint(drag.x,drag.y)?.closest(selector)
      drag.targetId=null
      if(!target||target.dataset.blockId===drag.id)return
      const rect=target.getBoundingClientRect()
      const peers=[...target.parentElement.children].filter(el=>el.matches(selector)&&el!==target)
      const horizontal=drag.surface==='preview'&&peers.some(el=>Math.abs(el.getBoundingClientRect().top-rect.top)<5)
      drag.targetId=target.dataset.blockId;drag.axis=horizontal?'horizontal':'vertical'
      drag.after=horizontal?drag.x>rect.left+rect.width/2:drag.y>rect.top+rect.height/2
    },
    scrollWhileDragging() {
      const drag=this.dragState;if(!drag?.active)return
      const edge=64;const delta=drag.y<edge?-Math.min(16,(edge-drag.y)/3):drag.y>innerHeight-edge?Math.min(16,(drag.y-innerHeight+edge)/3):0
      if(delta){window.scrollBy(0,delta);this.updateDropTarget()}
      this.scrollDragFrame=requestAnimationFrame(this.scrollWhileDragging)
    },
    finishBlockDrag(event) {
      const drag=this.dragState;if(!drag||event.pointerId!==drag.pointerId)return
      if(drag.type==='canvas'){const block=this.blocks.find(b=>b.id===drag.id);if(block&&drag.active){block.layout=this.roundCanvasLayout(drag.layout);this.notify(drag.action==='resize'?'已调整模块大小。':'已调整模块位置。')}const {id}=drag;this.cancelBlockDrag();this.focusDragHandle(id,'preview');return}
      if(drag.active&&drag.targetId){const index=this.blocks.findIndex(b=>b.id===drag.id);const target=this.blocks.findIndex(b=>b.id===drag.targetId);if(index>=0&&target>=0){const [block]=this.blocks.splice(index,1);const destination=this.blocks.findIndex(b=>b.id===drag.targetId)+(drag.after?1:0);this.blocks.splice(destination,0,block);this.notify(`已将「${block.title||'未命名内容'}」移到第 ${destination+1} 位。`)}}
      const {id,surface}=drag;this.cancelBlockDrag();this.focusDragHandle(id,surface)
    },
    cancelBlockDrag() {
      const drag=this.dragState;this.dragState=null;cancelAnimationFrame(this.scrollDragFrame)
      if(drag?.handle.hasPointerCapture(drag.pointerId))drag.handle.releasePointerCapture(drag.pointerId)
    },
    handleDragEscape(event) { if(event.key==='Escape'&&this.dragState){event.preventDefault();this.cancelBlockDrag();this.notify('已取消移动，原顺序保留。')} },
    focusDragHandle(id,surface) { nextTick(()=>document.querySelector(`${surface==='preview'?'.topic-block':'.structure-item'}[data-block-id="${id}"] .drag-handle`)?.focus({preventScroll:true})) },
    handleBlockDragKeys(event,id,surface) {
      if(!['ArrowUp','ArrowDown'].includes(event.key))return
      event.preventDefault();const index=this.blocks.findIndex(b=>b.id===id);const direction=event.key==='ArrowUp'?-1:1
      if(index+direction<0||index+direction>=this.blocks.length)return
      this.moveBlock(index,direction);this.notify(`已移到第 ${index+direction+1} 位。`);this.focusDragHandle(id,surface)
    },
    dropClass(id,surface) { const drag=this.dragState;if(!drag?.active||drag.type!=='sort'||drag.surface!==surface)return {};return {'drag-origin':drag.id===id,'drop-before':drag.targetId===id&&!drag.after,'drop-after':drag.targetId===id&&drag.after,'drop-horizontal':drag.targetId===id&&drag.axis==='horizontal'} },
    defaultCanvasLayout(index) { return {x:index%2?52:3,y:Math.floor(index/2)*270+20,w:45,h:240} },
    roundCanvasLayout(layout) { const w=Number(layout.w.toFixed(1));return {x:Math.min(100-w,Number(layout.x.toFixed(1))),y:Math.round(layout.y),w,h:Math.round(layout.h)} },
    canvasLayout(block,index) { return this.dragState?.type==='canvas'&&this.dragState.id===block.id?this.dragState.layout:(block.layout||this.defaultCanvasLayout(index)) },
    canvasBlockStyle(block,index) { if(this.layoutMode!=='canvas'||this.canvasStacked)return {};const layout=this.canvasLayout(block,index);return {left:layout.x+'%',top:layout.y+'px',width:layout.w+'%',height:layout.h+'px'} },
    updateNarrowCanvas() { this.cancelBlockDrag();this.narrowCanvas=innerWidth<=760 },
    beginCanvasTransform(event,id,action='move') {
      if(this.canvasStacked||this.previewMode!=='edit'||event.button!==0)return
      this.cancelBlockDrag();event.preventDefault();event.currentTarget.focus();this.selectedBlock=id
      const block=this.blocks.find(b=>b.id===id);const index=this.blocks.indexOf(block);const canvas=event.currentTarget.closest('.topic-content');if(!block||!canvas)return
      const layout=clone(this.canvasLayout(block,index));this.dragState={type:'canvas',id,surface:'preview',action,pointerId:event.pointerId,startX:event.clientX,startY:event.clientY,x:event.clientX,y:event.clientY,scrollY:window.scrollY,canvasWidth:canvas.clientWidth,initial:clone(layout),layout,active:false,handle:event.currentTarget}
      event.currentTarget.setPointerCapture(event.pointerId)
    },
    updateCanvasTransform() {
      const drag=this.dragState;if(drag?.type!=='canvas')return
      const dx=(drag.x-drag.startX)/drag.canvasWidth*100,dy=drag.y-drag.startY+window.scrollY-drag.scrollY
      if(Math.abs(dx)+Math.abs(dy)>2)drag.active=true
      const initial=drag.initial
      if(drag.action==='move'){drag.layout={...initial,x:Math.max(0,Math.min(100-initial.w,initial.x+dx)),y:Math.max(0,Math.min(10000,initial.y+dy))}}
      else {drag.layout={...initial,w:Math.max(20,Math.min(100-initial.x,initial.w+dx)),h:Math.max(120,Math.min(1600,initial.h+dy))}}
    },
    handlePreviewMoveKeys(event,id) {
      if(this.layoutMode!=='canvas'){this.handleBlockDragKeys(event,id,'preview');return}
      if(this.canvasStacked||!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(event.key))return
      event.preventDefault();const index=this.blocks.findIndex(b=>b.id===id),block=this.blocks[index];const layout=clone(this.canvasLayout(block,index));const step=event.shiftKey?10:1
      if(event.key==='ArrowLeft')layout.x=Math.max(0,layout.x-step);if(event.key==='ArrowRight')layout.x=Math.min(100-layout.w,layout.x+step);if(event.key==='ArrowUp')layout.y=Math.max(0,layout.y-step*10);if(event.key==='ArrowDown')layout.y=Math.min(10000,layout.y+step*10)
      block.layout=layout;this.notify('已调整模块位置。')
    },
    setCanvasDimension(key,event) {
      const block=this.activeBlock;if(!block)return;const value=Number(event.target.value);if(!Number.isFinite(value))return
      const layout=clone(this.canvasLayout(block,this.blocks.indexOf(block)))
      if(key==='x')layout.x=Math.max(0,Math.min(100-layout.w,value));if(key==='y')layout.y=Math.max(0,Math.min(10000,value));if(key==='w')layout.w=Math.max(20,Math.min(100-layout.x,value));if(key==='h')layout.h=Math.max(120,Math.min(1600,value))
      block.layout=layout;event.target.value=layout[key]
    },
    addBlock() { const block={id:uid(),title:'新内容',body:'',kind:'text'};if(this.layoutMode==='canvas')block.layout={x:3,y:this.canvasHeight,w:60,h:240};this.blocks.push(block);this.selectedBlock=block.id;this.previewMode='edit' },
    deleteBlock() { const index=this.blocks.findIndex(b=>b.id===this.selectedBlock);if(index<0)return;this.removedBlock={index,block:clone(this.blocks[index])};this.blocks.splice(index,1);this.selectedBlock=this.blocks[Math.min(index,this.blocks.length-1)]?.id },
    undoDelete() { if(!this.removedBlock)return;const {index,block}=this.removedBlock;this.blocks.splice(Math.min(index,this.blocks.length),0,block);this.selectedBlock=block.id;this.removedBlock=null },
    saveDraft() {
      if(!this.generatedForm.topic.trim()){this.notify('先给专题起一个名字。');return false}
      const id=this.currentId||uid();const draft={version:1,id,form:clone(this.generatedForm),brief:clone(this.generationBrief),briefRecovered:this.briefRecovered,blocks:clone(this.blocks),layoutMode:this.layoutMode,updatedAt:new Date().toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}),withoutSource:this.generatedWithoutSource}
      const next=[draft,...this.drafts.filter(d=>d.id!==id)]
      try { localStorage.setItem(storageKey,JSON.stringify(next));this.drafts=next;this.currentId=id;this.savedFingerprint=this.fingerprint;this.savedNotice=true;this.storageError='';location.hash=`draft/${id}`;return true } catch { this.storageError='浏览器无法保存演示草稿，当前编辑仍保留。请检查存储权限或空间。';this.savedNotice=false;return false }
    },
    openDraft(id) { if(id===this.currentId&&this.view==='workbench')return;this.requestNavigation({kind:'draft',id}) },
    requestNavigation(target) {
      this.focusOrigin=document.activeElement
      if(this.hasUnsavedWork||this.candidate){this.pendingNavigation=target;this.questionnaireOpen=false;this.restoreWorkspaceHash();return}
      this.performNavigation(target)
    },
    restoreWorkspaceHash() { history.replaceState(null,'',this.generatedForm.topic?`#${this.currentId?'draft/'+this.currentId:'workbench'}`:'#chat') },
    resolveNavigation(save) {
      const target=this.pendingNavigation;if(!target)return
      if(save&&!this.saveDraft())return
      this.pendingNavigation=null;this.performNavigation(target)
    },
    cancelNavigation() { this.pendingNavigation=null;this.restoreWorkspaceHash();nextTick(()=>this.focusOrigin?.focus()) },
    performNavigation(target) {
      this.generationRun++;this.questionnaireOpen=false;this.candidate=null;this.removedBlock=null;this.savedNotice=false;this.previewMode='edit'
      if(target.kind==='draft'){
        const draft=this.drafts.find(d=>d.id===target.id);if(!draft){this.notify('当前浏览器没有这个演示草稿。');return}
        this.generatedForm=clone(draft.form);this.generationBrief=clone(draft.brief||draft.form);this.briefRecovered=!draft.brief||Boolean(draft.briefRecovered);this.form=clone(draft.form);this.blocks=clone(draft.blocks);this.layoutMode=draft.layoutMode==='canvas'?'canvas':'flow';this.selectedBlock=this.blocks[0]?.id;this.generatedWithoutSource=Boolean(draft.withoutSource);this.currentId=draft.id;this.savedFingerprint=this.fingerprint;this.view='workbench';location.hash=`draft/${draft.id}`
      }else {
        this.form=blankForm();this.generatedForm=blankForm();this.generationBrief=blankForm();this.briefRecovered=false;this.generationInput=null;this.blocks=[];this.currentId=null;this.selectedBlock=null;this.conversation=[];this.chatInput='';this.activeExample=null;this.layoutMode='flow';this.view='chat';this.savedFingerprint=this.fingerprint;location.hash='chat'
      }
      window.scrollTo(0,0)
    },
    warnUnsaved(event) { if(this.hasUnsavedWork||this.candidate){event.preventDefault();event.returnValue=''} },
    routeFromHash() { const hash=location.hash.slice(1);if(hash.startsWith('draft/')){const id=hash.slice(6);if(this.currentId!==id)this.openDraft(id)}else if(hash==='chat'&&this.view!=='chat')this.goChat() },
    goChat() { this.requestNavigation({kind:'chat'}) }
  }
}).mount('#app')
