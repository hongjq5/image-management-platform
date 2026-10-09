import { readFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import ts from 'typescript'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, nextTick } from 'vue'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
})

// Compile the real SFC setup/module; replace only browser/network boundaries.
export function loadSource(relative, mocks = {}, globals = {}, cache = new Map()) {
  const filename = path.resolve(root, relative)
  if (cache.has(filename)) return cache.get(filename)
  let source = readFileSync(filename, 'utf8')
  if (filename.endsWith('.vue')) {
    source = compileScript(parse(source, { filename }).descriptor, { id: filename }).content
  }
  source = source.replaceAll('import.meta.env', '__testEnv')
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
  }).outputText
  const module = { exports: {} }
  cache.set(filename, module.exports)
  const localRequire = (name) => {
    if (name in mocks) return mocks[name]
    if (name.startsWith('@/') || name.startsWith('.')) {
      let target = name.startsWith('@/') ? path.join(root, 'src', name.slice(2)) : path.resolve(path.dirname(filename), name)
      if (!existsSync(target)) target += '.ts'
      return loadSource(path.relative(root, target), mocks, globals, cache)
    }
    return require(name)
  }
  vm.runInNewContext(code, {
    module, exports: module.exports, require: localRequire, console, URL, URLSearchParams,
    setTimeout, clearTimeout, __testEnv: {}, ...globals,
  }, { filename })
  return module.exports
}

export async function mountSetup(relative, { props = {}, mocks = {}, globals = {} } = {}) {
  const component = loadSource(relative, mocks, globals).default
  let state
  const app = renderer.createApp({
    setup(_, context) {
      state = component.setup(props, context)
      return () => null
    },
  })
  app.mount({})
  await nextTick()
  await new Promise(setImmediate)
  return { state, unmount: () => app.unmount() }
}

export const silentMessage = { success() {}, error() {}, info() {}, warning() {} }
