export const styles = [
  {
    id: 'guide',
    name: '清晰指南',
    description: '浅色背景、单栏阅读、明显章节层级，适中留白，突出行动步骤。',
    suitable: '适合：经验分享、操作指南',
  },
  {
    id: 'cards',
    name: '问题卡片',
    description: '内容按卡片分组，突出问题标题和行动建议，方便快速定位。',
    suitable: '适合：避坑、问题速查',
  },
  {
    id: 'timeline',
    name: '路线时间线',
    description: '用编号与连接线组织节点，突出先后顺序，沿路线逐步阅读。',
    suitable: '适合：看番路线、学习路径',
  },
  {
    id: 'docs',
    name: '学习文档',
    description: '目录与标题层级清晰，内容紧凑、装饰较少，适合反复查阅。',
    suitable: '适合：工具学习、参考文档',
  },
]
export const examples = [
  {
    id: 'rent',
    icon: '⌂',
    short: '大学生短租',
    hint: '整理经验，少踩坑',
    topic: '大学生实习短租指南',
    audience: '第一次外地实习的大学生',
    purpose: '避坑解决问题',
    goal: '了解短租需要准备的事情，建立自己的找房与看房判断顺序。',
    style: 'guide',
    context: '中国大陆城市实习，租期约 1—6 个月',
    source:
      '# 我的短租经验\n\n## 先明确自己的需求\n我先整理了租期、预算、通勤和是否合租的要求，并记录哪些条件可以协商。\n\n## 找房与看房准备\n整理想问的问题。\n记录看房观察与房源说明。\n把各个选项放在一起比较。\n\n## 信息不一致怎么办？\n把不清楚的信息列成问题，再向相关方确认，并保留自己的记录。以上仅为案例演示资料，需作者结合真实情境核验。',
  },
  {
    id: 'anime',
    icon: '▷',
    short: '看番路线',
    hint: '安排顺序，串起故事',
    topic: '新手动画观看路线',
    audience: '第一次接触这个系列的观众',
    purpose: '看番路线',
    goal: '了解建议观看次序，按自己的时间安排一次完整观看。',
    style: 'timeline',
    context: '作品名称、上映年份与分支顺序由作者补充',
    source:
      '# 我的观看路线\n\n## 从这里开始\n先认识主要角色和故事背景。本段由作者补充具体作品，不默认生成不存在的片名。\n\n## 接着看什么\n主线第一部分。\n主线第二部分。\n根据兴趣选择番外。\n\n## 番外一定要看吗？\n由作者解释番外与主线的关系，并注明自己的推荐依据。',
  },
  {
    id: 'tool',
    icon: '⌘',
    short: '学习一项工具',
    hint: '从入门到第一次完成',
    topic: '工具入门实践路线',
    audience: '没有使用经验的工具新手',
    purpose: '学习某项工具',
    goal: '完成准备、第一次操作和一个小练习，知道如何检查结果。',
    style: 'docs',
    context: '具体工具名称与版本由作者补充',
    source:
      '# 工具入门\n\n## 先准备什么\n说明适用版本、已有基础和本次练习材料。\n\n## 完成第一次操作\n打开工具并确认环境。\n按照作者提供的真实操作步骤完成练习。\n记录结果并核对预期。\n\n## 遇到失败怎样检查？\n记录错误信息，检查前置条件，再查阅对应版本的官方资料。',
  },
]
export const blankForm = () => ({
  topic: '',
  audience: '',
  purpose: '',
  goal: '',
  style: 'guide',
  density: 'comfortable',
  styleNote: '',
  source: '',
  constraints: '',
  context: '',
})
