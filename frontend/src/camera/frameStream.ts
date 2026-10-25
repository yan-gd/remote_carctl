/** Length-prefixed JPEG stream: uint32 big endian length, then bytes; 0 = keepalive. */
export async function readFrames(
  body: ReadableStream<Uint8Array>,
  onFrame: (jpeg: Uint8Array) => Promise<void>,
  signal: AbortSignal,
) {
  const reader = body.getReader();
  let buffer = new Uint8Array(0);
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      if (done) break;
      if (buffer.length + value.length > 2 * 384 * 1024 + 8) throw new Error('视频帧缓冲超过限制。');
      const combined = new Uint8Array(buffer.length + value.length);
      combined.set(buffer);
      combined.set(value, buffer.length);
      buffer = combined;
      while (buffer.length >= 4 && !signal.aborted) {
        const size = new DataView(buffer.buffer, buffer.byteOffset, 4).getUint32(0);
        if (size > 384 * 1024) throw new Error('视频帧超过384KiB限制。');
        if (buffer.length < size + 4) break;
        const jpeg = buffer.slice(4, size + 4);
        buffer = buffer.slice(size + 4);
        if (size > 0) await onFrame(jpeg);
      }
    }
    if (!signal.aborted && buffer.length) throw new Error('视频连接结束时存在不完整帧。');
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
