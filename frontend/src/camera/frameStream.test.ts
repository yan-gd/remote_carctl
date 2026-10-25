import { describe, it, expect, vi } from 'vitest';
import { readFrames } from './frameStream';

function body(chunks: Uint8Array[]) {
  return new ReadableStream<Uint8Array>({start(controller) {
    chunks.forEach(chunk=>controller.enqueue(chunk));controller.close();
  }});
}
describe('transient camera frame protocol',()=>{
  it('joins split headers and frames and skips heartbeat packets',async()=>{
    const received=vi.fn(async()=>undefined);
    await readFrames(body([new Uint8Array([0,0]),new Uint8Array([0,0,0,0,0,3,1]),new Uint8Array([2,3,0,0,0,1,4])]),received,new AbortController().signal);
    expect(received.mock.calls.map(args=>Array.from((args as unknown as [Uint8Array])[0]))).toEqual([[1,2,3],[4]]);
  });
  it('rejects oversized lengths and truncated frames without decoding them',async()=>{
    const received=vi.fn(async()=>undefined);
    await expect(readFrames(body([new Uint8Array([0,7,0,0])]),received,new AbortController().signal)).rejects.toThrow('384KiB');
    await expect(readFrames(body([new Uint8Array([0,0,0,5,1])]),received,new AbortController().signal)).rejects.toThrow('不完整帧');
    expect(received).not.toHaveBeenCalled();
  });
  it('aborted viewers receive no more frames',async()=>{
    const controller=new AbortController();controller.abort();
    const received=vi.fn(async()=>undefined);
    await readFrames(body([new Uint8Array([0,0,0,1,1])]),received,controller.signal);
    expect(received).not.toHaveBeenCalled();
  });
});
