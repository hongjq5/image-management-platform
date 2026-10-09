<template>
  <a-modal
    class="image-cropper"
    v-model:visible="visible"
    title="编辑图片"
    :footer="false"
    @cancel="closeModal"
  >
    <a-alert v-if="imageLoadError" type="error" message="图片加载失败，请关闭后重试或刷新页面" />
    <!-- Load signed images as local blobs before the cropper reads their pixels. -->
    <a-spin :spinning="imageLoading" tip="正在加载图片">
      <div class="cropper-frame">
        <vue-cropper
          v-if="cropperImage"
          ref="cropperRef"
          :img="cropperImage"
          output-type="png"
          :info="true"
          :can-move-box="canEdit"
          :fixed-box="!canEdit"
          :auto-crop="true"
          :center-box="true"
          :can-move="canEdit"
          :can-scale="canEdit"
          @img-load="onImageLoad"
        />
      </div>
    </a-spin>
    <div style="margin-bottom: 16px" />
    <!-- 协同编辑操作 -->
    <div class="image-edit-actions" v-if="isTeamSpace">
      <a-space>
        <a-button v-if="editingUser" disabled>{{ editingUser.userName }} 正在编辑</a-button>
        <a-button v-if="canEnterEdit" type="primary" ghost @click="enterEdit">进入编辑</a-button>
        <a-button v-if="canExitEdit" danger ghost @click="exitEdit">退出编辑</a-button>
      </a-space>
    </div>
    <div style="margin-bottom: 16px" />
    <!-- 图片操作 -->
    <div class="image-cropper-actions">
      <a-space>
        <a-button @click="rotateLeft" :disabled="!canEdit || !imageReady">向左旋转</a-button>
        <a-button @click="rotateRight" :disabled="!canEdit || !imageReady">向右旋转</a-button>
        <a-button @click="changeScale(1)" :disabled="!canEdit || !imageReady">放大</a-button>
        <a-button @click="changeScale(-1)" :disabled="!canEdit || !imageReady">缩小</a-button>
        <a-button type="primary" :loading="loading" :disabled="!canEdit || !imageReady" @click="handleConfirm"
          >确认
        </a-button>
      </a-space>
    </div>
  </a-modal>
</template>

<script lang="ts" setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { uploadPictureUsingPost } from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import PictureEditWebSocket from '@/utils/pictureEditWebSocket.ts'
import { PICTURE_EDIT_ACTION_ENUM, PICTURE_EDIT_MESSAGE_TYPE_ENUM } from '@/constants/picture.ts'
import { SPACE_TYPE_ENUM } from '@/constants/space.ts'

interface Props {
  imageUrl?: string
  picture?: API.PictureVO
  spaceId?: API.Id
  space?: API.SpaceVO
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

// 是否为团队空间
const isTeamSpace = computed(() => {
  return props.space?.spaceType === SPACE_TYPE_ENUM.TEAM
})

// 获取图片裁切器的引用
interface Cropper {
  changeScale: (amount: number) => void
  rotateLeft: () => void
  rotateRight: () => void
  getCropBlob: (callback: (blob: Blob) => void) => void
}
const cropperRef = ref<Cropper>()

// 缩放比例
const changeScale = (num: number) => {
  if (!canEdit.value || !imageReady.value) return
  cropperRef.value?.changeScale(num)
  if (num > 0) {
    editAction(PICTURE_EDIT_ACTION_ENUM.ZOOM_IN)
  } else {
    editAction(PICTURE_EDIT_ACTION_ENUM.ZOOM_OUT)
  }
}

// 向左旋转
const rotateLeft = () => {
  if (!canEdit.value || !imageReady.value) return
  cropperRef.value?.rotateLeft()
  editAction(PICTURE_EDIT_ACTION_ENUM.ROTATE_LEFT)
}

// 向右旋转
const rotateRight = () => {
  if (!canEdit.value || !imageReady.value) return
  cropperRef.value?.rotateRight()
  editAction(PICTURE_EDIT_ACTION_ENUM.ROTATE_RIGHT)
}

// 确认裁切
const handleConfirm = () => {
  if (!canEdit.value || !imageReady.value || loading.value) return
  cropperRef.value?.getCropBlob((blob: Blob) => {
    // blob 为已经裁切好的文件
    const fileName = (props.picture?.name || 'image') + '.png'
    const file = new File([blob], fileName, { type: blob.type })
    // 上传图片
    handleUpload({ file })
  })
}

const loading = ref(false)

/**
 * 上传图片
 * @param file
 */
const handleUpload = async ({ file }: { file: File }) => {
  loading.value = true
  try {
    const params: API.PictureUploadRequest = props.picture ? { id: props.picture.id } : {}
    params.spaceId = props.spaceId
    const res = await uploadPictureUsingPost(params, {}, file)
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
      closeModal()
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch (error) {
    console.error('图片上传失败', error)
    message.error('图片上传失败，请稍后重试')
  }
  loading.value = false
}

// 是否可见
const visible = ref(false)
const cropperImage = ref('')
const imageLoading = ref(false)
const imageReady = ref(false)
const imageLoadError = ref(false)

const onImageLoad = computed(() => {
  const source = cropperImage.value
  return (status: string) => {
    if (!source || source !== cropperImage.value) return
    imageLoading.value = false
    imageReady.value = status === 'success'
    imageLoadError.value = !imageReady.value
  }
})

watch([visible, () => props.imageUrl], async ([isVisible, imageUrl], _previous, onCleanup) => {
  cropperImage.value = ''
  imageReady.value = false
  imageLoadError.value = false
  imageLoading.value = isVisible && !!imageUrl
  if (!isVisible) return
  if (!imageUrl) {
    imageLoadError.value = true
    return
  }
  const controller = new AbortController()
  let objectUrl = ''
  let active = true
  onCleanup(() => {
    active = false
    controller.abort()
    if (objectUrl) URL.revokeObjectURL(objectUrl)
  })
  try {
    // COS omits Vary: Origin. Bypass responses cached by ordinary preview images
    // without changing any signed URL parameters or sending application cookies.
    const response = await fetch(imageUrl, {
      mode: 'cors', credentials: 'omit', cache: 'no-store', signal: controller.signal,
    })
    if (!response.ok) throw new Error('Image request failed')
    const blob = await response.blob()
    if (!active) return
    objectUrl = URL.createObjectURL(blob)
    cropperImage.value = objectUrl
  } catch {
    if (!active) return
    imageLoading.value = false
    imageLoadError.value = true
  }
}, { flush: 'sync' })

// 打开弹窗
const openModal = () => {
  visible.value = true
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false
  disconnect()
}

// 暴露函数给父组件
defineExpose({
  openModal,
  closeModal,
})

// --------- 实时编辑 ---------
const loginUserStore = useLoginUserStore()
const connected = ref(false)
const ownsEditLock = ref(false)

// 正在编辑的用户
const editingUser = ref<API.UserVO>()
// 当前用户是否可进入编辑
const canEnterEdit = computed(() => {
  return connected.value && !!loginUserStore.loginUser.id && !editingUser.value
})
// Ownership is session-specific: other tabs of the same account remain viewers.
const canExitEdit = computed(() => {
  return connected.value && ownsEditLock.value
})
// 可以点击编辑图片的操作按钮
const canEdit = computed(() => {
  if ((props.picture?.spaceId ?? props.spaceId) != null && !props.space) return false
  // 不是团队空间，默认就可以编辑
  if (!isTeamSpace.value) {
    return true
  }
  // 团队空间，只有编辑者才能协同编辑
  return canExitEdit.value
})

// 编写 WebSocket 逻辑
let websocket: PictureEditWebSocket | null = null

const disconnect = () => {
  websocket?.disconnect()
  websocket = null
  connected.value = false
  ownsEditLock.value = false
  editingUser.value = undefined
}

// 初始化 WebSocket 连接，绑定监听事件
const initWebsocket = () => {
  const pictureId = props.picture?.id
  if (!pictureId || !visible.value) {
    return
  }
  disconnect()
  // 创建 websocket 实例
  websocket = new PictureEditWebSocket(pictureId)
  websocket.on('open', () => { connected.value = true })
  websocket.on('close', () => {
    connected.value = false
    ownsEditLock.value = false
    editingUser.value = undefined
    message.warning('协作连接已断开，请关闭并重新打开编辑器')
  })
  websocket.on('error', () => {
    connected.value = false
    ownsEditLock.value = false
    editingUser.value = undefined
    message.error('协作连接失败，请关闭并重新打开编辑器')
  })

  // 监听一系列的事件
  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.INFO, (msg) => {
    if (msg.message) message.info(msg.message)
  })

  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.ERROR, (msg) => {
    if (msg.message) message.error(msg.message)
  })

  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.ENTER_EDIT, (msg) => {
    if (msg.message) message.info(msg.message)
    editingUser.value = msg.user
    ownsEditLock.value = msg.isEditor === true
  })

  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.EDIT_ACTION, (msg) => {
    // Received changes are applied locally only, including other tabs of the same user.
    switch (msg.editAction) {
      case PICTURE_EDIT_ACTION_ENUM.ROTATE_LEFT:
        cropperRef.value?.rotateLeft()
        break
      case PICTURE_EDIT_ACTION_ENUM.ROTATE_RIGHT:
        cropperRef.value?.rotateRight()
        break
      case PICTURE_EDIT_ACTION_ENUM.ZOOM_IN:
        cropperRef.value?.changeScale(1)
        break
      case PICTURE_EDIT_ACTION_ENUM.ZOOM_OUT:
        cropperRef.value?.changeScale(-1)
        break
    }
  })

  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.EXIT_EDIT, (msg) => {
    if (msg.message) message.info(msg.message)
    editingUser.value = undefined
    ownsEditLock.value = false
  })
  websocket.connect()
}

// 监听属性和 visible 变化，初始化 WebSocket 连接
watch([visible, isTeamSpace, () => props.picture?.id, () => loginUserStore.loginUser.id], () => {
  if (visible.value && isTeamSpace.value && loginUserStore.loginUser.id) {
    initWebsocket()
  } else {
    disconnect()
  }
})

// 组件销毁时，断开 WebSocket 连接
onUnmounted(disconnect)

// 进入编辑状态
const enterEdit = () => {
  if (websocket && canEnterEdit.value) {
    // 发送进入编辑状态的请求
    websocket.sendMessage({
      type: PICTURE_EDIT_MESSAGE_TYPE_ENUM.ENTER_EDIT,
    })
  }
}

// 退出编辑状态
const exitEdit = () => {
  if (websocket && canExitEdit.value) {
    // 发送退出编辑状态的请求
    websocket.sendMessage({
      type: PICTURE_EDIT_MESSAGE_TYPE_ENUM.EXIT_EDIT,
    })
  }
}

// 编辑图片操作
const editAction = (action: string) => {
  if (websocket) {
    // 发送编辑操作的请求
    websocket.sendMessage({
      type: PICTURE_EDIT_MESSAGE_TYPE_ENUM.EDIT_ACTION,
      editAction: action,
    })
  }
}
</script>

<style>
.image-cropper {
  text-align: center;
}

.image-cropper .cropper-frame,
.image-cropper .vue-cropper {
  height: 400px !important;
}
</style>
