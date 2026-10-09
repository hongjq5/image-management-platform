// vue-cropper 1.1.4 publishes declarations that import its untyped source SFC.
// The app consumes only the installable plugin; the cropper instance is typed locally.
import type { Plugin } from 'vue'
declare const VueCropper: Plugin
export default VueCropper
