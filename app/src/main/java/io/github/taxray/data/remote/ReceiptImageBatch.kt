package io.github.taxray.data.remote

/** Bounds apply to metadata-free JPEG bytes, before Base64 encoding or any API call. */
object ReceiptImageBatch {
    const val MAX_IMAGES = 5
    const val MAX_IMAGE_BYTES = 1_000_000
    const val MAX_TOTAL_BYTES = 5_000_000

    fun validate(images: List<ByteArray>) {
        require(images.size in 1..MAX_IMAGES) { "请选择 1 至 $MAX_IMAGES 张账单图片" }
        require(images.all { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }) {
            "单张图片需压缩至 1 MB 以内 请重新选择图片"
        }
        require(images.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) {
            "图片合计超过 5 MB 请减少图片后重试"
        }
    }
}
