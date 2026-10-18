<script setup>
// 登录失效后的就地重新登录面板（配合 lib/sessionGuard.js）。页面里未提交的内容保持原样。
defineProps({ guard: { type: Object, required: true } })
</script>
<template>
  <section v-if="guard.expired.value" class="account-panel account-reauth" role="alert" aria-label="重新登录">
    <h2>登录状态已变化，修改仍保留</h2>
    <p>请使用原账号重新登录，再继续提交。换用其他账号不能提交这些内容。</p>
    <form class="account-form" novalidate @submit.prevent="guard.reLogin()">
      <label>邮箱<input v-model="guard.email.value" type="email" autocomplete="email" /></label>
      <label>登录密码<input v-model="guard.password.value" type="password" autocomplete="current-password" /></label>
      <p v-if="guard.error.value" class="account-error" role="alert">{{ guard.error.value }}</p>
      <button class="btn btn-primary" :disabled="guard.busy.value">{{ guard.busy.value ? '登录中…' : '重新登录并继续' }}</button>
    </form>
  </section>
</template>
