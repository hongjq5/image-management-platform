import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick, reactive } from 'vue'
import { mountSetup, silentMessage } from './helpers.mjs'

const signedUrl = 'https://images.example.test/photo.webp?q-signature=test-signature&q-key-time=1%3B2'
const mocks = {
  'ant-design-vue': { message: silentMessage },
  '@/api/pictureController.ts': {},
  '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => ({ loginUser: {} }) },
}

test('cropper loads signed bytes without reusing a non-CORS preview cache and releases them on close', async () => {
  const revoked = []
  const requests = []
  const image = new Blob(['test image bytes'], { type: 'image/webp' })
  const modal = await mountSetup('src/components/ImageCropper.vue', {
    props: { imageUrl: signedUrl }, mocks,
    globals: {
      AbortController,
      URL: {
        createObjectURL(blob) { assert.equal(blob, image); return 'blob:test-cropper' },
        revokeObjectURL(url) { revoked.push(url) },
      },
      fetch: async (url, options) => {
        requests.push({ url, options })
        // A plain preview may already have cached a response without CORS headers.
        if (options.cache !== 'no-store' || options.mode !== 'cors') throw new TypeError('Cached response has no CORS headers')
        return { ok: true, blob: async () => image }
      },
    },
  })
  try {
    modal.state.openModal()
    await nextTick()
    await new Promise(setImmediate)
    assert.equal(modal.state.cropperImage?.value, 'blob:test-cropper', 'cropper must receive readable local image bytes')
    assert.equal(requests.length, 1)
    assert.equal(requests[0].url, signedUrl, 'signed query must remain unchanged')
    assert.equal(requests[0].options.credentials, 'omit')
    let exports = 0
    modal.state.cropperRef.value = { getCropBlob() { exports++ } }
    modal.state.handleConfirm()
    assert.equal(exports, 0, 'bytes must be decoded before confirming a crop')
    modal.state.onImageLoad.value('success')
    assert.equal(modal.state.imageReady.value, true)
    modal.state.handleConfirm()
    assert.equal(exports, 1)
    modal.state.closeModal()
    await nextTick()
    assert.equal(requests[0].options.signal.aborted, true)
    assert.deepEqual(revoked, ['blob:test-cropper'])
    assert.equal(modal.state.cropperImage.value, '')
  } finally { modal.unmount() }
})

test('failed image loading exposes an error and a late response cannot replace a newer image', async () => {
  const props = reactive({ imageUrl: signedUrl })
  let resolveOld
  const modal = await mountSetup('src/components/ImageCropper.vue', {
    props, mocks,
    globals: {
      AbortController,
      URL: { createObjectURL() { return 'blob:stale' }, revokeObjectURL() {} },
      fetch: (url) => url === signedUrl
        ? new Promise(resolve => { resolveOld = resolve })
        : Promise.resolve({ ok: false, status: 403 }),
    },
  })
  try {
    modal.state.openModal()
    await nextTick()
    props.imageUrl = 'https://images.example.test/new.webp?q-signature=expired'
    await nextTick()
    await new Promise(setImmediate)
    assert.equal(modal.state.imageLoadError?.value, true, 'loading errors must not silently leave an empty checkerboard')
    resolveOld({ ok: true, blob: async () => new Blob(['stale']) })
    await new Promise(setImmediate)
    assert.equal(modal.state.cropperImage.value, '')
    assert.equal(modal.state.imageReady.value, false)
  } finally { modal.unmount() }
})
