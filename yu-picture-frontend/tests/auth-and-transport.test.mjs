import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import axios from 'axios'
import { loadSource, mountSetup, silentMessage } from './helpers.mjs'

test('expired or failed session refresh clears the previous user', async () => {
  const module = loadSource('src/stores/useLoginUserStore.ts', {
    '@/api/userController.ts': { getLoginUserUsingGet: async () => ({ data: { code: 40100 } }) },
  })
  const store = module.useLoginUserStore(createPinia())
  store.setLoginUser({ id: '7', userRole: 'admin' })
  await store.fetchLoginUser()
  assert.equal(store.loginUser.id, undefined)
  assert.equal(store.loginUser.userRole, undefined)
})

test('login restores an internal redirect with its query intact', async () => {
  const destinations = []
  const page = await mountSetup('src/pages/user/UserLoginPage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@/router': { push: (route) => destinations.push(route), replace: (route) => destinations.push(route) },
      'vue-router': { useRoute: () => ({ query: { redirect: '/add_picture?id=42&spaceId=7' } }) },
      '@/api/userController.ts': { userLoginUsingPost: async () => ({ data: { code: 0, data: { id: '7' } } }) },
      '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => ({ fetchLoginUser: async () => {}, setLoginUser() {} }) },
    },
  })
  try {
    await page.state.handleSubmit({ userAccount: 'tester', userPassword: 'test12345' })
    assert.equal(typeof destinations[0] === 'string' ? destinations[0] : destinations[0].path, '/add_picture?id=42&spaceId=7')
  } finally { page.unmount() }
})

test('HTTPS deployment opens wss on same origin and stale socket callbacks cannot deliver messages', () => {
  const sockets = []
  class Socket {
    static OPEN = 1
    static CONNECTING = 0
    readyState = 0
    constructor(url) { this.url = url; sockets.push(this) }
    close() { this.readyState = 3 }
    send() {}
  }
  const Client = loadSource('src/utils/pictureEditWebSocket.ts', {}, {
    window: { location: { href: 'https://gallery.example/picture/42', origin: 'https://gallery.example', protocol: 'https:', host: 'gallery.example' } },
    WebSocket: Socket,
  }).default
  const client = new Client('1923456789012345678')
  let delivered = 0
  client.on('EDIT_ACTION', () => delivered++)
  client.connect()
  assert.equal(sockets[0].url, 'wss://gallery.example/api/ws/picture/edit?pictureId=1923456789012345678')
  const oldHandler = sockets[0].onmessage
  client.disconnect()
  oldHandler({ data: JSON.stringify({ type: 'EDIT_ACTION' }) })
  assert.equal(delivered, 0)
})

test('malformed collaboration frames are ignored without crashing subsequent messages', () => {
  const sockets = []
  class Socket {
    constructor() { sockets.push(this) }
  }
  const Client = loadSource('src/utils/pictureEditWebSocket.ts', {}, {
    window: { location: { href: 'http://localhost:5173/', origin: 'http://localhost:5173' } },
    WebSocket: Socket,
  }).default
  const client = new Client('7')
  let delivered = 0
  client.on('EDIT_ACTION', () => delivered++)
  client.connect()
  const socket = sockets[0]
  assert.doesNotThrow(() => socket.onmessage({ data: 'not JSON' }))
  socket.onmessage({ data: JSON.stringify({ type: 'EDIT_ACTION' }) })
  assert.equal(delivered, 1)
})

test('upload validation accepts static WebP along with JPEG and PNG and rejects oversize input', async () => {
  const uploader = await mountSetup('src/components/PictureUpload.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      '@ant-design/icons-vue': {},
      '@/api/pictureController.ts': {},
    },
  })
  try {
    for (const type of ['image/jpeg', 'image/png', 'image/webp']) {
      assert.equal(uploader.state.beforeUpload({ type, size: 1024 }), true, type)
    }
    assert.equal(uploader.state.beforeUpload({ type: 'image/webp', size: 3 * 1024 * 1024 }), false)
    assert.equal(uploader.state.beforeUpload({ type: 'text/html', size: 1024 }), false)
  } finally { uploader.unmount() }
})

test('route IDs retain decimal precision and unsafe login targets fall back to home', () => {
  const { routeId, loginRedirect } = loadSource('src/utils/route.ts')
  assert.equal(routeId('1923456789012345678'), '1923456789012345678')
  for (const value of [null, ['12', '13'], '', 'NaN', '-1']) assert.equal(routeId(value), undefined)
  for (const value of ['https://evil.example', '//evil.example', '/\\evil.example', '/user/login', null]) {
    assert.equal(loginRedirect(value), '/')
  }
})

test('generated API requests use exactly one API prefix and retain credentials and long IDs', async () => {
  for (const [base, expected] of [
    [undefined, '/api/space/edit'],
    ['https://api.example/custom', 'https://api.example/custom/space/edit'],
  ]) {
    const api = loadSource('src/api/spaceController.ts', { 'ant-design-vue': { message: silentMessage } }, {
      __testEnv: { VITE_API_BASE_URL: base },
    })
    let observed
    await api.editSpaceUsingPost({ id: '1923456789012345678', spaceName: 'Name' }, {
      adapter: async (config) => {
        observed = config
        return { data: { code: 0, data: true }, status: 200, statusText: 'OK', headers: {}, config }
      },
    })
    assert.equal(axios.getUri(observed), expected)
    assert.equal(observed.withCredentials, true)
    assert.equal(JSON.parse(observed.data).id, '1923456789012345678')
  }
})

test('expired protected request encodes the full internal return path without corrupting query parameters', async () => {
  const location = { pathname: '/add_picture', search: '?id=42&spaceId=7', hash: '#edit', href: '' }
  const request = loadSource('src/request.ts', { 'ant-design-vue': { message: silentMessage } }, {
    window: { location },
  }).default
  const adapter = async (config) => ({ data: { code: 40100 }, status: 200, statusText: 'OK', headers: {}, config })
  await request.get('/user/get/login', { adapter })
  assert.equal(location.href, '')
  await request.get('/space/get/vo', { adapter })
  const target = new URL(location.href, 'https://gallery.example')
  assert.equal(target.pathname, '/user/login')
  assert.equal(target.searchParams.get('redirect'), '/add_picture?id=42&spaceId=7#edit')
})

test('private image search page does not send its picture to reverse image search', async () => {
  const searches = []
  const page = await mountSetup('src/pages/SearchPicturePage.vue', {
    mocks: {
      'ant-design-vue': { message: silentMessage },
      'vue-router': { useRoute: () => ({ query: { pictureId: '42' } }) },
      '@/api/pictureController.ts': {
        getPictureVoByIdUsingGet: async () => ({ data: { code: 0, data: { id: '42', spaceId: '7' } } }),
        searchPictureByPictureUsingPost: async (body) => { searches.push(body); return { data: { code: 0, data: [] } } },
      },
    },
  })
  try {
    await page.state.fetchResultData()
    assert.equal(searches.length, 0)
    assert.equal(page.state.loading.value, false)
  } finally { page.unmount() }
})
