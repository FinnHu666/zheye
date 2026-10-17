import { nextTick } from 'vue'

export const HTML_BLOCK_VERSION = 1
export const MAX_HTML_LENGTH = 60000
const allowedTags = new Set('div section article main header footer aside nav span p h1 h2 h3 h4 h5 h6 strong b em i u s small mark sub sup br hr ol ul li dl dt dd blockquote pre code figure figcaption table caption thead tbody tfoot tr th td details summary a img'.split(' '))
const allowedAttributes = new Set('class id style title lang dir role aria-label aria-labelledby aria-describedby aria-hidden tabindex open start reversed value colspan rowspan scope headers width height alt loading'.split(' '))
const resourceCss = /@import\b|url\s*\(/i
const policy = "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

// Parse only inside an inert template. User markup never enters the host document.
export function compileHtmlBlock(source) {
  if(typeof source!=='string'||!source.trim())throw new Error('请先粘贴 HTML + CSS，或使用时间轴示例。')
  if(source.length>MAX_HTML_LENGTH)throw new Error('代码最多 60,000 字，请精简后再预览。')
  const template=document.createElement('template');template.innerHTML=source.replace(/\r\n?/g,'\n')
  let count=0,visible=false
  function inspect(parent,depth=0) {
    if(depth>40)throw new Error('模块嵌套超过 40 层，请精简结构。')
    for(const node of [...parent.childNodes]) {
      if(++count>2000)throw new Error('模块节点过多，请拆成多个模块。')
      if(node.nodeType===8){node.remove();continue}
      if(node.nodeType===3){if(node.textContent.trim())visible=true;continue}
      if(node.nodeType!==1){node.remove();continue}
      const tag=node.localName.toLowerCase()
      if(tag==='title'){node.remove();continue}
      if(tag==='meta'&&(node.hasAttribute('charset')||node.getAttribute('name')?.toLowerCase()==='viewport')&&!node.hasAttribute('http-equiv')){node.remove();continue}
      if(tag!=='style'&&!allowedTags.has(tag))throw new Error(`首轮不支持 <${tag}>。请使用静态 HTML + CSS；脚本、Vue 标签和外部嵌入暂不支持。`)
      if(tag==='style') {
        if(node.attributes.length)throw new Error('请使用不带属性的 <style> 标签。')
        if(resourceCss.test(node.textContent))throw new Error('CSS 外部资源与 url() 暂不支持，请使用本地样式。')
        continue
      }
      for(const attribute of [...node.attributes]) {
        const name=attribute.name.toLowerCase(),value=attribute.value
        if(name==='href'&&tag==='a'&&/^#[a-zA-Z0-9_-]+$/.test(value))continue
        if(name==='src'&&tag==='img'&&/^data:image\/(png|jpeg|gif|webp|avif);base64,[a-z0-9+/=\s]+$/i.test(value))continue
        if(!allowedAttributes.has(name)||name.startsWith('on'))throw new Error(`暂不支持属性 ${name}。事件、外部链接、脚本和外部资源不能导入。`)
        if(name==='style'&&resourceCss.test(value))throw new Error('行内样式中的 url() 暂不支持。')
      }
      if(tag==='img'&&!node.hasAttribute('src'))throw new Error('图片需要内嵌的 PNG/JPEG/GIF/WebP/AVIF 数据，首轮不加载外部图片。')
      if(tag==='img'||tag==='hr')visible=true
      inspect(node,depth+1)
    }
  }
  inspect(template.content)
  if(!visible)throw new Error('代码中还没有可显示的内容，请添加正文或节点。')
  const html=template.innerHTML
  const srcdoc=`<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="${policy}"><meta name="viewport" content="width=device-width, initial-scale=1"><style>*,*::before,*::after{box-sizing:border-box}html{color-scheme:light;background:#fff}body{margin:0;padding:16px;font:14px/1.7 system-ui,sans-serif;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body>${html}</body></html>`
  return {srcdoc,html,version:HTML_BLOCK_VERSION}
}

export const timelineExample = `<style>
.route { margin: 0; padding: 0 0 0 24px; list-style: none; border-left: 3px solid #9ab58b; }
.route li { position: relative; padding: 0 0 22px 12px; }
.route li::before { content: ""; position: absolute; left: -32px; top: 7px; width: 12px; height: 12px; border-radius: 50%; background: #43765a; }
.route strong { color: #245c45; font-size: 16px; }
.route p { margin: 6px 0 0; color: #65715e; }
@media (max-width: 400px) { .route { padding-left: 18px; } .route li::before { left: -26px; } }
</style>
<ol class="route">
  <li><strong>看房前</strong><p>明确预算、租期与通勤要求。</p></li>
  <li><strong>看房时</strong><p>观察房屋情况，把需要确认的事项记录下来。</p></li>
  <li><strong>入住前</strong><p>核对交接事项，保存自己的记录。</p></li>
</ol>`

export const HtmlBlockFrame = {
  props:{code:Object,title:String},
  computed:{
    compiled() { try { if(this.code?.rendererVersion!==HTML_BLOCK_VERSION)throw new Error('模块版本不支持，请重新检查代码。');return {...compileHtmlBlock(this.code.source),error:''} } catch(error) { return {error:error.message} } }
  },
  template:`<div v-if="compiled.error" class="html-frame-error" role="alert">{{ compiled.error }}</div><iframe v-else class="html-block-frame" sandbox="" referrerpolicy="no-referrer" :title="'HTML 模块：'+(title||'未命名模块')" :srcdoc="compiled.srcdoc" :style="{'--code-frame-height':(code.frameHeight||360)+'px'}"></iframe>`
}

export const HtmlCodeDialog = {
  emits:['close','commit'],
  props:{initialSource:{type:String,default:''},initialTitle:{type:String,default:''},initialHeight:{type:Number,default:360},editing:Boolean},
  data() { return {source:this.initialSource,name:this.initialTitle,height:this.initialHeight,checkedSource:null,previewDocument:'',error:'',fileError:'',loading:false,device:'desktop'} },
  computed:{ready() { return this.checkedSource===this.source&&Boolean(this.previewDocument)&&!this.loading&&Boolean(this.name.trim()) },validHeight() { return Number.isFinite(Number(this.height))&&Number(this.height)>=120&&Number(this.height)<=1200 }},
  mounted() { this.$refs.dialog.showModal();nextTick(()=>this.$refs.name.focus()) },
  beforeUnmount() { this.$refs.dialog?.close() },
  methods:{
    close() { if(!this.loading)this.$emit('close') },
    example() { this.source=timelineExample;if(!this.name.trim())this.name='租房行动时间轴';this.error='';this.fileError='' },
    inspect() { try { const result=compileHtmlBlock(this.source);this.previewDocument=result.srcdoc;this.checkedSource=this.source;this.error='' } catch(error) { this.error=error.message;this.checkedSource=null } },
    commit() { if(!this.ready)return;if(!this.validHeight){this.error='模块显示高度需要在 120—1200px 之间。';return}this.$emit('commit',{title:this.name.trim(),code:{rendererVersion:HTML_BLOCK_VERSION,source:this.source,frameHeight:Number(this.height)}}) },
    async readFile(event) {
      const file=event.target.files?.[0];if(!file)return
      this.loading=true;this.fileError=''
      try { if(!/\.html?$/i.test(file.name))throw new Error('请选择 .html 或 .htm 文件。');if(file.size>131072)throw new Error('文件最多 128 KiB。');const source=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());if(source.length>MAX_HTML_LENGTH)throw new Error('代码最多 60,000 字。');this.source=source;if(!this.name.trim())this.name=file.name.replace(/\.html?$/i,'').slice(0,100);this.checkedSource=null;this.error='' } catch(error) { this.fileError=error instanceof TypeError?'无法读取文件，请使用 UTF-8 编码。':error.message } finally { this.loading=false;event.target.value='' }
    }
  },
  template:`<dialog ref="dialog" class="code-dialog" aria-labelledby="code-dialog-title" @cancel.prevent="close">
    <header class="code-dialog-header"><div><span class="tiny-label">自定义 HTML + CSS</span><h2 id="code-dialog-title">{{ editing?'编辑模块代码':'导入一个自己的模块' }}</h2><p>先检查预览，确认后再添加。位置与大小仍由画布管理。</p></div><button type="button" class="close-button" aria-label="关闭代码导入" :disabled="loading" @click="close">×</button></header>
    <div class="code-dialog-grid"><section class="code-source-panel"><div class="code-source-meta"><label>模块名称<input ref="name" v-model="name" maxlength="100" :disabled="loading"></label><label>显示高度（px）<input type="number" v-model.number="height" min="120" max="1200" :disabled="loading"></label></div>
      <p v-if="!validHeight" class="field-error" role="alert">显示高度需要在 120—1200px 之间。</p><div class="code-source-actions"><button type="button" class="secondary" @click="example" :disabled="loading">使用时间轴示例</button><button type="button" class="text-button" @click="$refs.file.click()" :disabled="loading">{{ loading?'读取中…':'导入 HTML 文件' }}</button><input ref="file" type="file" accept=".html,.htm,text/html" hidden @change="readFile"></div>
      <label>HTML + CSS 代码<textarea class="code-source-input" v-model="source" spellcheck="false" rows="16" :disabled="loading" placeholder="粘贴包含 HTML 和 <style> 的代码…"></textarea></label><span class="code-count">{{ source.length.toLocaleString() }} / 60,000 字 · UTF-8 文件最多 128 KiB</span>
      <p v-if="fileError" class="field-error" role="alert">{{ fileError }}</p><p v-if="error" class="field-error" role="alert">{{ error }}</p><button type="button" class="secondary code-check" @click="inspect" :disabled="loading">检查并预览</button>
    </section><section class="code-result-panel"><div class="code-preview-heading"><strong>模块预览</strong><div class="segmented" aria-label="代码预览宽度"><button type="button" :class="{active:device==='desktop'}" @click="device='desktop'">宽屏预览</button><button type="button" :class="{active:device==='mobile'}" @click="device='mobile'">窄屏预览</button></div></div>
      <p v-if="previewDocument&&checkedSource!==source" class="code-preview-stale" role="status">代码已修改，请重新检查；下方保留上一次预览。</p><div class="code-preview-surface" :class="{narrow:device==='mobile'}"><iframe v-if="previewDocument" class="code-dialog-frame" sandbox="" referrerpolicy="no-referrer" title="导入代码预览" :srcdoc="previewDocument" :style="{height:(validHeight?height:360)+'px'}"></iframe><div v-else class="code-preview-empty">你的代码会在这里呈现。<br>可以先体验时间轴示例。</div></div>
      <p class="code-boundary">支持静态 HTML + CSS 和 details 折叠。首轮不执行脚本、不加载外部依赖；未知标签和属性会提示。手机容器会适配宽度，内部响应式样式由代码定义，超出高度可滚动。</p>
    </section></div><footer class="code-dialog-footer"><button type="button" class="text-button" @click="close" :disabled="loading">取消</button><span>{{ ready?'代码已检查，可以添加':'检查通过后才会更新页面' }}</span><button type="button" class="primary" :disabled="!ready||!validHeight" @click="commit">{{ editing?'确认更新模块':'添加到页面' }}</button></footer>
  </dialog>`
}
