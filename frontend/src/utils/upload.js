import { ElMessage } from 'element-plus'

/** 附件通用上限，与后端 spring.servlet.multipart 的限制保持同一量级 */
export const MAX_UPLOAD_MB = 20
/** 导入类（Excel 清册）单独给一档，行数多但仍要有顶 */
export const MAX_IMPORT_MB = 10

/**
 * 选文件后的前置校验：扩展名 + 大小。
 *
 * 后端有扩展名白名单和 multipart 上限兜底，但那是等整个文件传完才知道——
 * 用户拖一个 200MB 的视频进来，要等上传跑完才收到「不支持的文件类型」。
 * 前端先挡一道纯粹是省这趟往返，不替代后端校验。
 *
 * @param {File} file 原生 File 对象
 * @param {{exts?: string[], maxMB?: number, label?: string}} opt
 * @returns {boolean} 通过返回 true，不通过已经弹过提示
 */
export function checkUploadFile(file, opt = {}) {
  const { exts = [], maxMB = MAX_UPLOAD_MB, label = '文件' } = opt
  if (!file) { ElMessage.warning(`请选择${label}`); return false }
  if (exts.length) {
    const name = (file.name || '').toLowerCase()
    if (!exts.some(e => name.endsWith(e.toLowerCase()))) {
      ElMessage.warning(`${label}格式不支持，仅允许 ${exts.join(' / ')}`)
      return false
    }
  }
  if (file.size > maxMB * 1024 * 1024) {
    ElMessage.warning(`${label}不能超过 ${maxMB}MB`)
    return false
  }
  if (file.size === 0) { ElMessage.warning(`${label}是空文件`); return false }
  return true
}
