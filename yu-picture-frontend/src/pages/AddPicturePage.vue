<template>
  <div id="addPicturePage">
    <h2 style="margin-bottom: 16px">
      {{ route.query?.id ? '修改图片' : '创建图片' }}
    </h2>
    <a-typography-paragraph v-if="spaceId" type="secondary">
      保存至空间：<a :href="`/space/${spaceId}`" target="_blank">{{ spaceId }}</a>
    </a-typography-paragraph>
    <!-- 选择上传方式 -->
    <a-alert v-if="route.query.id && !picture" message="图片尚未加载，暂不能编辑或替换" type="info" />
    <a-tabs v-else v-model:activeKey="uploadType">
      <a-tab-pane key="file" tab="文件上传">
        <!-- 图片上传组件 -->
        <PictureUpload :key="contextVersion" :picture="picture" :spaceId="spaceId" :onSuccess="onSuccess" />
      </a-tab-pane>
      <a-tab-pane key="url" tab="URL 上传" force-render>
        <!-- URL 图片上传组件 -->
        <UrlPictureUpload :key="contextVersion" :picture="picture" :spaceId="spaceId" :onSuccess="onSuccess" />
      </a-tab-pane>
    </a-tabs>
    <!-- 图片编辑 -->
    <div v-if="picture" class="edit-bar">
      <a-space size="middle">
        <a-button :icon="h(EditOutlined)" @click="doEditPicture">编辑图片</a-button>
        <a-button disabled title="此版本未启用阿里云 AI 图片编辑">
          AI 扩图（未启用）
        </a-button>
      </a-space>
      <ImageCropper
        :key="contextVersion"
        ref="imageCropperRef"
        :imageUrl="picture?.url"
        :picture="picture"
        :spaceId="spaceId"
        :space="space"
        :onSuccess="onCropSuccess"
      />
    </div>
    <!-- 图片信息表单 -->
    <a-form
      v-if="picture"
      name="pictureForm"
      layout="vertical"
      :model="pictureForm"
      @finish="handleSubmit"
    >
      <a-form-item name="name" label="名称">
        <a-input v-model:value="pictureForm.name" placeholder="请输入名称" allow-clear />
      </a-form-item>
      <a-form-item name="introduction" label="简介">
        <a-textarea
          v-model:value="pictureForm.introduction"
          placeholder="请输入简介"
          :auto-size="{ minRows: 2, maxRows: 5 }"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="category" label="分类">
        <a-auto-complete
          v-model:value="pictureForm.category"
          placeholder="请输入分类"
          :options="categoryOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="tags" label="标签">
        <a-select
          v-model:value="pictureForm.tags"
          mode="tags"
          placeholder="请输入标签"
          :options="tagOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%">创建</a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import PictureUpload from '@/components/PictureUpload.vue'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  editPictureUsingPost,
  getPictureVoByIdUsingGet,
  listPictureTagCategoryUsingGet,
} from '@/api/pictureController.ts'
import { useRoute, useRouter } from 'vue-router'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'
import ImageCropper from '@/components/ImageCropper.vue'
import { EditOutlined } from '@ant-design/icons-vue'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'
import { routeId } from '@/utils/route.ts'

const router = useRouter()
const route = useRoute()

const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})
const uploadType = ref<'file' | 'url'>('file')
const contextVersion = ref(0)
// 空间 id
const spaceId = computed(() => {
  if (picture.value) return picture.value.spaceId
  if (route.query.id) return undefined
  return routeId(route.query.spaceId)
})

/**
 * 图片上传成功
 * @param newPicture
 */
const onSuccess = computed(() => {
  const version = contextVersion.value
  return (newPicture: API.PictureVO) => {
    if (version !== contextVersion.value) return
    picture.value = newPicture
    pictureForm.name = newPicture.name
  }
})

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: API.PictureEditRequest) => {
  const pictureId = picture.value?.id
  if (!pictureId) {
    return
  }
  const res = await editPictureUsingPost({
    id: pictureId,
    ...values,
  })
  // 操作成功
  if (res.data.code === 0 && res.data.data) {
    message.success('创建成功')
    // 跳转到图片详情页
    router.push({
      path: `/picture/${pictureId}`,
    })
  } else {
    message.error('创建失败，' + res.data.message)
  }
}

const categoryOptions = ref<{ value: string; label: string }[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])

/**
 * 获取标签和分类选项
 * @param values
 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

onMounted(() => {
  getTagCategoryOptions()
})

// ----- 图片编辑器引用 ------
const imageCropperRef = ref<{ openModal: () => void; closeModal: () => void }>()

// 编辑图片
const doEditPicture = async () => {
  imageCropperRef.value?.openModal()
}

// 编辑成功事件
const onCropSuccess = computed(() => {
  const version = contextVersion.value
  return (newPicture: API.PictureVO) => {
    if (version === contextVersion.value) picture.value = newPicture
  }
})

// 获取空间信息
const space = ref<API.SpaceVO>()

watch(
  [() => route.query.id, () => route.query.spaceId],
  async ([rawId], _previous, onCleanup) => {
    let cancelled = false
    onCleanup(() => { cancelled = true })
    imageCropperRef.value?.closeModal()
    picture.value = undefined
    space.value = undefined
    Object.assign(pictureForm, { name: undefined, introduction: undefined, category: undefined, tags: [] })
    contextVersion.value++
    const id = routeId(rawId)
    if (!id) return
    try {
      const res = await getPictureVoByIdUsingGet({ id })
      if (cancelled) return
      if (res.data.code === 0 && res.data.data) {
        const data = res.data.data
        picture.value = data
        Object.assign(pictureForm, {
          name: data.name, introduction: data.introduction, category: data.category, tags: data.tags,
        })
      } else {
        message.error('获取图片失败，' + res.data.message)
      }
    } catch {
      if (!cancelled) message.error('获取图片失败，请稍后重试')
    }
  },
  { immediate: true, flush: 'sync' },
)

watch([spaceId, contextVersion], async ([id], _previous, onCleanup) => {
  let cancelled = false
  onCleanup(() => { cancelled = true })
  space.value = undefined
  if (!id) return
  try {
    const res = await getSpaceVoByIdUsingGet({ id })
    if (cancelled) return
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
    } else {
      message.error('获取空间失败，' + res.data.message)
    }
  } catch {
    if (!cancelled) message.error('获取空间失败，请稍后重试')
  }
}, { immediate: true, flush: 'sync' })
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}

#addPicturePage .edit-bar {
  text-align: center;
  margin: 16px 0;
}
</style>
