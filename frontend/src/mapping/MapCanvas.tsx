import { useEffect, useRef, useState } from 'react';
import type { MapGrid, MapPose } from '../api/types';
import { freePixel, pixelToWorld, screenToPixel, worldToPixel } from './coordinates';

type XY={x:number;y:number};
export function MapCanvas({grid,pose,scan,path,selected,disabled,onSelect,onError}:{
  grid:MapGrid;pose:MapPose|null;scan:XY[];path:XY[];selected:MapPose|null;disabled:boolean;
  onSelect:(pose:MapPose)=>void;onError:(message:string)=>void;
}) {
  const [url,setUrl]=useState<string|null>(null);
  const [zoom,setZoom]=useState(1);
  const [center,setCenter]=useState<XY|null>(null);
  const [draft,setDraft]=useState<MapPose|null>(null);
  const drag=useRef<{pixel:XY;mapId:string;pointerId:number}|null>(null);
  const cells=atob(grid.cells);
  const view={x:(center?.x??grid.width/2)-grid.width/zoom/2,y:(center?.y??grid.height/2)-grid.height/zoom/2,
    width:grid.width/zoom,height:grid.height/zoom};
  useEffect(()=>{setZoom(1);setCenter(null);},[grid.session,grid.width,grid.height,grid.originX,grid.originY,grid.resolution]);
  useEffect(()=>{setDraft(null);drag.current=null;},[grid.mapId]);
  useEffect(()=>{
    let cancelled=false,owned:string|null=null;
    setUrl(null);
    const canvas=document.createElement('canvas');canvas.width=grid.width;canvas.height=grid.height;
    const ctx=canvas.getContext('2d');if(!ctx)return;
    const data=ctx.createImageData(grid.width,grid.height);
    for(let y=0;y<grid.height;y++)for(let x=0;x<grid.width;x++){
      const value=cells.charCodeAt(y*grid.width+x),offset=((grid.height-1-y)*grid.width+x)*4;
      const color=value===1?[235,239,233]:value===2?[39,54,58]:[143,158,164];
      data.data.set([...color,255],offset);
    }
    ctx.putImageData(data,0,0);
    canvas.toBlob(blob=>{if(!blob||cancelled)return;owned=URL.createObjectURL(blob);setUrl(owned);});
    return ()=>{cancelled=true;if(owned)URL.revokeObjectURL(owned);};
  },[grid,cells]);
  function point(e:React.PointerEvent<SVGSVGElement>){
    return screenToPixel(e.currentTarget.getBoundingClientRect(),view,{x:e.clientX,y:e.clientY});
  }
  function target(pixel:XY):MapPose|null {
    const start=drag.current;if(!start||start.mapId!==grid.mapId)return null;
    const from=pixelToWorld(grid,start.pixel),to=pixelToWorld(grid,pixel);
    return {...from,yaw:Math.hypot(pixel.x-start.pixel.x,pixel.y-start.pixel.y)<2?0:Math.atan2(to.y-from.y,to.x-from.x)};
  }
  function arrow(value:MapPose,color:string,label:string){
    const p=worldToPixel(grid,value),size=Math.max(grid.width,grid.height)/zoom/45;
    return <g aria-label={label} transform={`translate(${p.x} ${p.y}) rotate(${-(value.yaw-grid.originYaw)*180/Math.PI})`}>
      <circle r={size*0.7} fill={color} stroke="white" strokeWidth={size*0.1}/>
      <path d={`M ${size*1.5} 0 L ${-size*0.3} ${-size*0.5} L ${-size*0.3} ${size*0.5} Z`} fill={color} stroke="white" strokeWidth={size*0.1}/>
    </g>;
  }
  return <div className="web-map-view">
    <div className="map-toolbar">
      <span>浅色：可通行 · 深色：障碍 · 灰色：未知 · 橙色：激光</span>
      <button className="button" onClick={()=>setZoom(z=>Math.min(z*1.5,8))} aria-label="放大地图">＋</button>
      <button className="button" onClick={()=>setZoom(z=>Math.max(z/1.5,1))} aria-label="缩小地图">−</button>
      <button className="button" onClick={()=>{setZoom(1);setCenter(null);}}>全图</button>
      <button className="button" disabled={!pose} onClick={()=>{if(pose){setCenter(worldToPixel(grid,pose));setZoom(3);}}}>定位小车</button>
    </div>
    <svg className="map-surface" role="img" aria-label="实时地图，按下选择位置，拖动设置朝向" viewBox={`${view.x} ${view.y} ${view.width} ${view.height}`}
      onPointerDown={e=>{
        if(disabled||e.button!==0)return;
        const p=point(e);if(!freePixel(grid,cells,p)){onError('请选择地图中的已知可通行区域。');return;}
        drag.current={pixel:p,mapId:grid.mapId,pointerId:e.pointerId};e.currentTarget.setPointerCapture?.(e.pointerId);
        setDraft({...pixelToWorld(grid,p),yaw:0});
      }}
      onPointerMove={e=>{if(drag.current?.pointerId===e.pointerId)setDraft(target(point(e)));}}
      onPointerUp={e=>{if(disabled||drag.current?.pointerId!==e.pointerId)return;const p=target(point(e));drag.current=null;setDraft(null);if(p)onSelect(p);}}
      onPointerCancel={()=>{drag.current=null;setDraft(null);}} onLostPointerCapture={()=>{drag.current=null;setDraft(null);}}>
      <rect x="0" y="0" width={grid.width} height={grid.height} fill="#8f9ea4"/>
      {url&&<image href={url} width={grid.width} height={grid.height}/>}
      {path.length>1&&<polyline points={path.map(p=>{const v=worldToPixel(grid,p);return `${v.x},${v.y}`;}).join(' ')} fill="none" stroke="#00a680" strokeWidth="2" vectorEffect="non-scaling-stroke"/>}
      {scan.map((p,i)=>{const v=worldToPixel(grid,p);return <circle key={i} cx={v.x} cy={v.y} r={Math.max(grid.width,grid.height)/zoom/350} fill="#e06b25"/>;})}
      {pose&&arrow(pose,'#147cc1','小车位置')}
      {(draft??selected)&&arrow((draft??selected)!,'#b44393','选中位置')}
    </svg>
    <div className="map-toolbar map-pan">
      {([['←',-1,0],['↑',0,-1],['↓',0,1],['→',1,0]] as const).map(([label,x,y])=><button key={label} className="button" aria-label={`平移地图${label}`}
        onClick={()=>setCenter(c=>({x:(c?.x??grid.width/2)+x*view.width/4,y:(c?.y??grid.height/2)+y*view.height/4}))}>{label}</button>)}
      <span>地图分辨率 {grid.resolution.toFixed(2)} 米/格 · 拖动箭头设置朝向</span>
    </div>
  </div>;
}
