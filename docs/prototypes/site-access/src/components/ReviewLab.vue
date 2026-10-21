<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { proto } from '../mock/server'
import { ensureReview, loadReviewScene, REVIEW_PERMISSION } from '../mock/review'
import { simulateSignIn } from '../mock/user-admin'
const open=ref(false), settings=ref({}), identity=ref(''), router=useRouter()
function refresh(){const d=proto.db();settings.value={...ensureReview(d).settings};identity.value=d.users.find(u=>u.id===d.session.userId)?.displayName || '游客'}
function emit(){window.dispatchEvent(new Event('finns-account-session'));window.dispatchEvent(new Event('finns-review-scene'))}
function switchTo(id,path){proto.update(()=>simulateSignIn(id));emit();router.push(path);open.value=false}
function seed(){proto.update(()=>{loadReviewScene();simulateSignIn(1)});emit();router.push('/admin/reviews');open.value=false}
function longSample(){let taskId;proto.update(d=>{loadReviewScene();simulateSignIn(1);taskId=d.review.tasks.find(t=>t.docId===1701&&t.status==='PENDING').id});emit();router.push('/admin/reviews/tasks/'+taskId);open.value=false}
function highSample(){let taskId;proto.update(d=>{loadReviewScene();simulateSignIn(4);taskId=d.review.tasks.find(t=>t.docId===1709&&t.status==='PENDING').id});emit();router.push('/admin/reviews/tasks/'+taskId);open.value=false}
function set(key,value){proto.update(d=>ensureReview(d).settings[key]=value);refresh()}
function revoke(){proto.update(d=>{d.permissions.REVIEW_DEMO=(d.permissions.REVIEW_DEMO||[]).filter(p=>p!==REVIEW_PERMISSION)});emit()}
function retrySubmission(){window.dispatchEvent(new Event('finns-review-retry'))}
function stopAuthor(){proto.update(d=>{d.users.find(u=>u.id===3).accountStatus='DISABLED'});emit()}
onMounted(()=>{refresh();window.addEventListener('finns-proto-change',refresh)})
onBeforeUnmount(()=>window.removeEventListener('finns-proto-change',refresh))
</script>
<template><button class="rv-lab-toggle" :aria-expanded="open" @click="open=!open">◇ 审核演示 · R3</button><aside v-if="open" class="proto-console rv-lab" aria-label="内容审核原型演示工具"><header><div><strong>内容审核演示 · R3</strong><small>浏览器虚构数据，不修改真实账号</small></div><button aria-label="关闭审核演示" @click="open=false">×</button></header><section><p class="pc-muted">当前：{{identity}}。请先载入协作样例，演示面板会明确授予虚构审核员审批权。</p><div class="pc-actions"><button @click="seed">载入审核协作样例</button><button @click="longSample">载入长文审阅样例</button><button @click="highSample">审核员 · 高敏感复核样例</button><button @click="switchTo(1,'/admin/reviews')">站长 · 审批队列</button><button @click="switchTo(3,'/me/submissions?id=1709')">阿远 · 看流转过程</button><button @click="switchTo(4,'/admin/reviews')">审核员 · 仅内容权限</button><button @click="switchTo(2,'/admin/reviews')">管理员 · 无审批权</button><button @click="switchTo(null,'/review/reading')">游客 · 读者预览</button><button @click="switchTo(1,'/admin/sensitive-rules')">站长 · 敏感规则</button></div><details><summary>异常与权限场景</summary><label class="pc-check"><input type="checkbox" :checked="settings.detectFailure" @change="set('detectFailure',$event.target.checked)"/>敏感检测异常</label><label class="pc-check"><input type="checkbox" :checked="settings.failWriteOnce" @change="set('failWriteOnce',$event.target.checked)"/>下一次保存/审批失败</label><label class="pc-check"><input type="checkbox" :checked="settings.conflictOnce" @change="set('conflictOnce',$event.target.checked)"/>下一次处理状态冲突</label><label class="pc-check"><input type="checkbox" :checked="settings.failLoadOnce" @change="set('failLoadOnce',$event.target.checked)"/>下一次列表读取失败</label><label class="pc-check"><input type="checkbox" :checked="settings.slowLoad" @change="set('slowLoad',$event.target.checked)"/>列表慢加载</label><div class="pc-actions"><button @click="revoke">在别处撤销演示审核员权限</button><button @click="stopAuthor">在别处停用作者阿远</button><button @click="retrySubmission">重试上一次作者提交（不重复生成流程）</button></div></details><p class="pc-muted">作者正文填写「演示低敏词」放行并留痕、「演示拦截词」送审、「演示高敏词」自动不通过并复核；也可试 d.e.m.o 之类插入标点的变形写法。专题与文章流程一致（首次发布无命中即发布）。站长本人内容不可自审。恢复初始样例仅重置本次审核演示数据。</p></section></aside></template>
