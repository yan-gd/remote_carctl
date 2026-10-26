import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { MapGrid, MappingState, MapPose, MapRequest } from '../api/types';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';
import { useHoldControl } from '../control/useHoldControl';
import { MapCanvas } from './MapCanvas';

const MODES={IDLE:'等待建图或加载地图',MAPPING:'正在建图',NAVIGATION:'已加载导航地图',ERROR:'地图进程异常'};
const MESSAGES:Record<string,string>={
  START_APPLIED:'建图进程已启动，等待雷达生成地图。',SAVE_APPLIED:'地图已保存在小车，可以加载并设置初始位置。',
  LOAD_APPLIED:'地图已加载。请在图上设置小车实际位置和朝向，并核对橙色激光与障碍轮廓是否对齐。',
  STOP_APPLIED:'建图与地图导航已结束。',INITIAL_POSE_APPLIED:'初始位姿已发送，请核对激光与地图对齐后开始导航。',
  ROBOT_MUST_BE_STOPPED:'未确认小车已停稳，请检查真实速度与底盘状态。',
  EXTERNAL_MAP_OR_NAVIGATION_NODE:'检测到另外运行的建图或导航节点，请先关闭旧进程。',
  POSE_NOT_IN_FREE_MAP_SPACE:'初始位置周围存在障碍或未知区域，请重新选择。',
  FileExistsError:'同名地图已存在，请换一个新名字保存。',ROS_PROCESS_EXITED:'建图或导航进程已退出，请检查小车端日志。',
  MAP_CHANGED:'地图已变化，请重新选点。',ROS_LAUNCH_FAILED:'ROS 建图或导航未能启动，请检查小车端日志。',
  MAP_LOAD_TIMEOUT:'加载后未收到地图，请检查小车端 map_server 日志。',
};

export function MappingPanel({api,role,mission,onGoal,onReference}:{api:ApiClient;role:string;mission:boolean;
  onGoal:(pose:MapPose,mapId:string)=>void;onReference:(mapId:string|null)=>void}) {
  const [state,setState]=useState<MappingState|null>(null);
  const [grid,setGrid]=useState<MapGrid|null>(null);
  const [error,setError]=useState<string|null>(null);
  const [linkError,setLinkError]=useState<string|null>(null);
  const [name,setName]=useState('');const [saved,setSaved]=useState('');
  const [pick,setPick]=useState<'goal'|'initial'>('goal');
  const [selection,setSelection]=useState<{pose:MapPose;mapId:string}|null>(null);
  const [sending,setSending]=useState(false);
  const [notice,setNotice]=useState<string|null>(null);
  const [pressed,setPressed]=useState<string|null>(null);
  const alive=useRef(true),reference=useRef(onReference);reference.current=onReference;
  const canMove=role==='OPERATOR',canStop=canMove||role==='MONITOR';
  const live=!!state?.online,robot=state?.robot;
  const busy=sending||!!robot?.busy||!!state?.pending;
  const controller=useHoldControl(api,e=>{setPressed(null);setError(errorMessage(e));});
  const mapping=live&&robot?.mode==='MAPPING';
  const canPick=canMove&&live&&!busy&&!mission&&robot?.mode==='NAVIGATION'&&grid?.mapId===robot.mapId;

  useEffect(()=>{
    alive.current=true;let disposed=false,inFlight=false,known:string|null=null,lastGood=0;
    const refresh=async()=>{
      if(inFlight)return;inFlight=true;
      try{
        const next=await api.mappingStatus();if(disposed)return;
        lastGood=Date.now();setState(next);
        setLinkError(null);
        const id=next.online&&next.robot?.mode==='NAVIGATION'?next.robot.mapId:null;
        reference.current(id);
        if(!next.online||!next.robot?.mapId){setGrid(null);known=null;}
        else if(known!==next.robot.mapId){
          try {
            const fresh=await api.mappingGrid();if(disposed)return;
            if(fresh.mapId===next.robot.mapId&&fresh.session===next.robot.session){known=fresh.mapId;setGrid(fresh);}
          } catch {
            // A newly generated map can arrive after its status. Keep polling;
            // this does not mean the robot status/control connection is offline.
            if(!disposed){setGrid(null);known=null;}
          }
        }
      }catch(e){if(!disposed){setLinkError(errorMessage(e));setState(old=>old?{...old,online:false}:null);setGrid(null);known=null;reference.current(null);}}
      finally{inFlight=false;}
    };
    void refresh();const timer=window.setInterval(()=>{
      if(Date.now()-lastGood>5000){setState(old=>old?{...old,online:false}:null);reference.current(null);}
      void refresh();
    },1000);
    return()=>{disposed=true;alive.current=false;window.clearInterval(timer);};
  },[api]);
  useEffect(()=>{setSelection(null);},[robot?.mapId,robot?.session,pick]);
  useEffect(()=>{
    const stop=()=>{setPressed(null);void controller.stop();};
    const hidden=()=>{if(document.hidden)stop();};
    window.addEventListener('blur',stop);document.addEventListener('visibilitychange',hidden);
    return()=>{window.removeEventListener('blur',stop);document.removeEventListener('visibilitychange',hidden);};
  },[controller]);
  useEffect(()=>{if(!mapping||busy){setPressed(null);void controller.stop();}},[mapping,busy,controller]);

  async function command(request:MapRequest){
    setSending(true);setError(null);setNotice(null);
    try{
      await controller.stop();
      await api.mapCommand(request);
      if(!alive.current)return;
      setNotice('操作已提交，等待小车回执。');
      setState(await api.mappingStatus());
    }catch(e){if(alive.current)setError(errorMessage(e));}
    finally{if(alive.current)setSending(false);}
  }
  function end(){setPressed(null);void controller.stop();}
  const feedback=robot?.message&&(MESSAGES[robot.message]??robot.message);
  return <Card title="地图工作台" className="mapping-panel" actions={<StatusPill tone={live?'ok':'warn'}>{live?MODES[robot!.mode]:'建图服务未连接'}</StatusPill>}>
    <p className="muted">① 开始建图并遥控采集 → ② 保存并加载地图 → ③ 设置小车位置 → ④ 在地图上选目标并开始导航</p>
    {error&&<ErrorNotice message={error}/>}
    {linkError&&<ErrorNotice message={linkError}/>}
    {feedback&&<p className={`notice ${robot?.result==='FAILED'?'notice-error':'notice-info'}`}>{feedback}</p>}
    {state?.delivery==='EXPIRED'&&<p className="notice notice-error">操作未在有效期内送达，请确认小车在线后重试。</p>}
    {notice&&<p className="fine-print">{notice}</p>}
    <div className="mapping-controls">
      <button className="button primary" disabled={!canMove||!live||busy||mission||mapping} onClick={()=>void command({action:'START'})}>开始新建地图</button>
      <label>新地图名称<input aria-label="新地图名称" placeholder="例如 lab-floor1" maxLength={40} value={name} disabled={!canMove||busy} onChange={e=>setName(e.target.value)}/></label>
      <button className="button" disabled={!canMove||!mapping||busy||!robot?.mapId||!/^[-\w]{1,40}$/.test(name)} onClick={()=>void command({action:'SAVE',name})}>保存地图</button>
      <label>已保存地图<select aria-label="已保存地图" value={saved} disabled={!canMove||busy} onChange={e=>setSaved(e.target.value)}>
        <option value="">选择地图</option>{robot?.maps.map(n=><option key={n} value={n}>{n}</option>)}
      </select></label>
      <button className="button" disabled={!canMove||!live||busy||mission||!saved||!robot?.maps.includes(saved)} onClick={()=>void command({action:'LOAD',name:saved})}>加载地图导航</button>
      <button className="button" disabled={!canMove||!live||busy||mission||robot?.mode==='IDLE'} onClick={()=>void command({action:'STOP'})}>结束地图作业</button>
    </div>
    {!live&&<p className="notice notice-info">等待小车连接 Web 建图服务。连接后，可在这里完成建图与导航，无需打开小车图形界面。</p>}
    {mapping&&<div className="mapping-drive">
      <span>按住遥控采集地图，松开即停止。请缓慢绕行并回到起点附近。</span>
      {(['LEFT','FORWARD','BACKWARD','RIGHT'] as const).map((value,i)=><button key={value} className={`button ${pressed===value?'primary':''}`}
        disabled={!canMove||busy} aria-label={`建图${['左转','前进','后退','右转'][i]}`}
        onPointerDown={e=>{if(e.button!==0)return;e.preventDefault();e.currentTarget.setPointerCapture?.(e.pointerId);setPressed(value);void controller.start(value);}}
        onPointerUp={end} onPointerCancel={end} onLostPointerCapture={end}
        onKeyDown={e=>{if((e.key===' '||e.key==='Enter')&&!e.repeat){e.preventDefault();setPressed(value);void controller.start(value);}}}
        onKeyUp={e=>{if(e.key===' '||e.key==='Enter'){e.preventDefault();end();}}} onBlur={end}>{['← 左转','↑ 前进','↓ 后退','→ 右转'][i]}</button>)}
      <button className="button danger" disabled={!canStop} onClick={()=>{setPressed(null);void controller.stop(true);}}>立即停车</button>
    </div>}
    {grid&&live?<>
      <div className="mapping-controls">
        <label>地图操作<select aria-label="地图操作" value={pick} onChange={e=>setPick(e.target.value as 'goal'|'initial')} disabled={!canMove||busy||mission}>
          <option value="goal">选择导航目标</option><option value="initial">设置小车初始位置</option>
        </select></label>
        <span>{mapping?'地图正在更新，建图完成并加载后可选点。':pick==='initial'?'在小车实际位置按下，再沿车头方向拖动。':'在目标位置按下，拖动指定到达时的朝向。'}</span>
      </div>
      <MapCanvas grid={grid} pose={robot?.pose??null} scan={robot?.scan??[]} path={robot?.path??[]} selected={selection?.mapId===grid.mapId?selection.pose:null}
        disabled={!canPick} onError={setError} onSelect={pose=>{setError(null);setSelection({pose,mapId:grid.mapId});}}/>
      {selection&&<div className="mapping-controls">
        <span>选中位置：X {selection.pose.x.toFixed(2)} m，Y {selection.pose.y.toFixed(2)} m</span>
        <label>朝向（弧度）<input type="number" step="0.1" min={-Math.PI} max={Math.PI} aria-label="地图选点朝向" value={Math.max(-Math.PI,Math.min(Math.PI,Number(selection.pose.yaw.toFixed(3))))}
          onChange={e=>{const yaw=Number(e.target.value);if(Number.isFinite(yaw)&&Math.abs(yaw)<=Math.PI)setSelection({...selection,pose:{...selection.pose,yaw}});}}/></label>
        <button className="button primary" disabled={!canPick||selection.mapId!==robot?.mapId} onClick={()=>{
          if(pick==='initial')void command({action:'INITIAL_POSE',mapId:selection.mapId,pose:selection.pose});
          else {onGoal(selection.pose,selection.mapId);setNotice('目标已加入下方点位列表，点击“开始导航”后小车才会移动。');}
        }}>{pick==='initial'?'确认小车初始位置':'加入导航目标'}</button>
      </div>}
    </>:<div className="map-empty">{mapping?'等待第一张实时地图，请确认雷达和里程计正常。':'开始建图或加载已保存地图后，这里将显示真实地图。'}</div>}
    {robot?.mode==='NAVIGATION'&&<p className="fine-print">蓝色箭头是小车位置，橙色点是实时激光，绿色线是近期规划路径。设置初始位置后，请先核对激光轮廓与地图对齐。</p>}
  </Card>;
}
