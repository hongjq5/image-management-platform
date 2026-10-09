import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick, reactive } from 'vue'
import { mountSetup, silentMessage } from './helpers.mjs'

test('space owner saves through edit endpoint and returns to original ID after boolean response', async () => {
  const requests = []
  const destinations = []
  const page = await mountSetup('src/pages/AddSpacePage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '../utils': { formatSize: String },
      'vue-router': { useRoute: () => ({ query: {} }), useRouter: () => ({ push: (to) => destinations.push(to) }) },
      '@/api/spaceController.ts': {
        listSpaceLevelUsingGet: async () => ({ data: { code: 0, data: [] } }),
        editSpaceUsingPost: async (body) => { requests.push({ route: '/space/edit', body }); return { data: { code: 0, data: true } } },
        updateSpaceUsingPost: async (body) => { requests.push({ route: '/space/update', body }); return { data: { code: 0, data: true } } },
      },
    },
  })
  try {
    page.state.space.value = { id: '1923456789012345678', spaceLevel: 0 }
    page.state.spaceForm.spaceName = 'Renamed team'
    await page.state.handleSubmit()
    assert.equal(requests[0].route, '/space/edit')
    assert.equal(requests[0].body.id, '1923456789012345678')
    assert.equal(destinations[0].path, '/space/1923456789012345678')
    assert.equal(page.state.loading.value, false)
  } finally { page.unmount() }
})

test('same-account viewer cannot edit; owner loses controls when connection closes or space changes', async () => {
  const connections = []
  class Socket {
    constructor() { connections.push(this); this.handlers = {}; this.closed = false }
    connect() { this.handlers.open?.({}) }
    disconnect() { this.closed = true }
    on(type, handler) { this.handlers[type] = handler }
    sendMessage() { return true }
  }
  const props = reactive({ picture: { id: '42' }, space: { spaceType: 1 } })
  const modal = await mountSetup('src/components/ImageCropper.vue', {
    props,
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@/api/pictureController.ts': {},
      '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => ({ loginUser: { id: '7' } }) },
      '@/utils/pictureEditWebSocket.ts': Socket,
    },
  })
  try {
    modal.state.openModal()
    await nextTick()
    const connection = connections[0]
    connection.handlers.ENTER_EDIT({ user: { id: '7' }, isEditor: false })
    assert.equal(modal.state.canEdit.value, false, 'same account is not proof of socket lock ownership')
    connection.handlers.ENTER_EDIT({ user: { id: '7' }, isEditor: true })
    assert.equal(modal.state.canEdit.value, true)
    connection.handlers.close({})
    assert.equal(modal.state.canEdit.value, false)
    props.space = { spaceType: 0 }
    await nextTick()
    assert.equal(connection.closed, true)
  } finally { modal.unmount() }
})

test('failed space save releases loading state', async () => {
  const page = await mountSetup('src/pages/AddSpacePage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '../utils': { formatSize: String },
      'vue-router': { useRoute: () => ({ query: {} }), useRouter: () => ({ push() {} }) },
      '@/api/spaceController.ts': {
        listSpaceLevelUsingGet: async () => ({ data: { code: 0, data: [] } }),
        addSpaceUsingPost: async () => { throw new Error('offline') },
      },
    },
  })
  try {
    await page.state.handleSubmit().catch(() => {})
    assert.equal(page.state.loading.value, false)
  } finally { page.unmount() }
})

test('editing a team picture by ID alone loads its authoritative space before enabling the cropper', async () => {
  const requestedSpaces = []
  const page = await mountSetup('src/pages/AddPicturePage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@ant-design/icons-vue': {},
      '@/components/PictureUpload.vue': {},
      '@/components/UrlPictureUpload.vue': {},
      '@/components/ImageCropper.vue': {},
      'vue-router': { useRoute: () => ({ query: { id: '42' } }), useRouter: () => ({ push() {} }) },
      '@/api/pictureController.ts': {
        getPictureVoByIdUsingGet: async () => ({ data: { code: 0, data: { id: '42', spaceId: '88' } } }),
        listPictureTagCategoryUsingGet: async () => ({ data: { code: 0, data: {} } }),
      },
      '@/api/spaceController.ts': {
        getSpaceVoByIdUsingGet: async (params) => {
          requestedSpaces.push(params.id)
          return { data: { code: 0, data: { id: '88', spaceType: 1 } } }
        },
      },
    },
  })
  try {
    await nextTick()
    assert.equal(page.state.spaceId.value, '88')
    assert.equal(requestedSpaces[0], '88')
    assert.equal(page.state.space.value.spaceType, 1)
  } finally { page.unmount() }

  const modal = await mountSetup('src/components/ImageCropper.vue', {
    props: { picture: { id: '42', spaceId: '88' }, spaceId: '88' },
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@/api/pictureController.ts': {},
      '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => ({ loginUser: { id: '7' } }) },
    },
  })
  try {
    assert.equal(modal.state.canEdit.value, false, 'unknown space metadata must not be treated as public')
  } finally { modal.unmount() }
})

test('switching from editing to creating clears old picture and ignores a late detail response', async () => {
  const route = reactive({ query: { id: '42' } })
  let resolveDetail
  let pending = false
  const page = await mountSetup('src/pages/AddPicturePage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@ant-design/icons-vue': {},
      '@/components/PictureUpload.vue': {},
      '@/components/UrlPictureUpload.vue': {},
      '@/components/ImageCropper.vue': {},
      'vue-router': { useRoute: () => route, useRouter: () => ({ push() {} }) },
      '@/api/pictureController.ts': {
        getPictureVoByIdUsingGet: async () => pending
          ? new Promise((resolve) => { resolveDetail = resolve })
          : { data: { code: 0, data: { id: '42', name: 'Old name' } } },
        listPictureTagCategoryUsingGet: async () => ({ data: { code: 0, data: {} } }),
      },
      '@/api/spaceController.ts': {},
    },
  })
  try {
    assert.equal(page.state.picture.value.id, '42')
    route.query = {}
    await nextTick()
    assert.equal(page.state.picture.value, undefined, 'create must never reuse previous picture ID')
    assert.equal(page.state.pictureForm.name, undefined)
    pending = true
    route.query = { id: '43' }
    await nextTick()
    route.query = {}
    await nextTick()
    resolveDetail({ data: { code: 0, data: { id: '43', name: 'Late name' } } })
    await new Promise(setImmediate)
    assert.equal(page.state.picture.value, undefined, 'late detail response must not replace new context')
    assert.equal(page.state.pictureForm.name, undefined)
  } finally { page.unmount() }
})

test('switching to new upload in the same space reloads context and ignores the old upload callback', async () => {
  const route = reactive({ query: { id: '42' } })
  const requestedSpaces = []
  const page = await mountSetup('src/pages/AddPicturePage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@ant-design/icons-vue': {},
      '@/components/PictureUpload.vue': {},
      '@/components/UrlPictureUpload.vue': {},
      '@/components/ImageCropper.vue': {},
      'vue-router': { useRoute: () => route, useRouter: () => ({ push() {} }) },
      '@/api/pictureController.ts': {
        getPictureVoByIdUsingGet: async () => ({ data: { code: 0, data: { id: '42', spaceId: '88' } } }),
        listPictureTagCategoryUsingGet: async () => ({ data: { code: 0, data: {} } }),
      },
      '@/api/spaceController.ts': {
        getSpaceVoByIdUsingGet: async ({ id }) => {
          requestedSpaces.push(id)
          return { data: { code: 0, data: { id, spaceType: 1 } } }
        },
      },
    },
  })
  try {
    const oldUploadCallback = page.state.onSuccess.value ?? page.state.onSuccess
    const initialCalls = requestedSpaces.length
    route.query = { spaceId: '88' }
    await new Promise(setImmediate)
    assert.equal(page.state.space.value?.id, '88', 'same space context must be reloaded after clearing')
    assert.ok(requestedSpaces.length > initialCalls)
    oldUploadCallback({ id: '42', spaceId: '88' })
    assert.equal(page.state.picture.value, undefined, 'old upload cannot repopulate the create page')
  } finally { page.unmount() }
})

test('incoming rotation and zoom apply once without sending another edit frame', async () => {
  const connections = []
  const sent = []
  const applied = []
  class Socket {
    constructor() { connections.push(this); this.handlers = {} }
    connect() { this.handlers.open?.({}) }
    disconnect() {}
    on(type, handler) { this.handlers[type] = handler }
    sendMessage(frame) { sent.push(frame); return true }
  }
  const modal = await mountSetup('src/components/ImageCropper.vue', {
    props: { picture: { id: '1923456789012345678' }, space: { spaceType: 1 } },
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@/api/pictureController.ts': {},
      '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => ({ loginUser: { id: '7' } }) },
      '@/utils/pictureEditWebSocket.ts': Socket,
    },
  })
  try {
    modal.state.cropperRef.value = {
      rotateLeft: () => applied.push('left'), rotateRight: () => applied.push('right'),
      changeScale: (value) => applied.push(value),
    }
    modal.state.openModal()
    await nextTick()
    const connection = connections[0]
    connection.handlers.ENTER_EDIT({ user: { id: '7' }, isEditor: true })
    for (const editAction of ['ROTATE_LEFT', 'ROTATE_RIGHT', 'ZOOM_IN', 'ZOOM_OUT']) {
      connection.handlers.EDIT_ACTION({ editAction, user: { id: '7' } })
    }
    assert.deepEqual(applied, ['left', 'right', 1, -1])
    assert.equal(sent.length, 0, 'received frames must not be re-broadcast even for another tab of the same user')
  } finally { modal.unmount() }
})
