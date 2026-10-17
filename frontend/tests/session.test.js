import test from 'node:test'
import assert from 'node:assert/strict'
import { currentUser, can } from '../src/lib/session.js'

test('guests only see public content; signed-in users follow the permissions returned by the server', () => {
  currentUser.value = null
  assert.equal(can('content.public'), true)
  assert.equal(can('post.create'), false)

  currentUser.value = { id: 1, role: { code: 'RESTRICTED', name: '受限成员' }, permissions: ['content.public', 'profile.manage', 'selection.manage'] }
  assert.equal(can('selection.manage'), true)
  assert.equal(can('post.create'), false)
  assert.equal(can('admin.users'), false)

  currentUser.value = { id: 1, role: { code: 'R7', name: '审核员' } } // 缺少 permissions 时按无权限处理
  assert.equal(can('content.public'), false)
  currentUser.value = null
})
