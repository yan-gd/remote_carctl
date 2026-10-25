import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { NavigationStatus } from '../api/types';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';

const REASONS: Record<string,string> = {
  NAVIGATION_DISABLED:'后端导航尚未启用',NO_LIVE_NAVIGATION_REPORT:'未收到新鲜导航报告',MAP_UNAVAILABLE:'没有可用的map地图',
  SENSOR_OR_COSTMAP_STALE:'雷达、底盘或局部代价地图数据缺失/过期',MOVE_BASE_UNAVAILABLE:'move_base没有启动',
  LOCALIZATION_UNAVAILABLE:'map → base_link定位不可用',DIRECT_CMD_VEL_PUBLISHER:'检测到其他节点直接控制底盘，请先隔离导航速度话题',
  INITIAL_POSE_REQUIRED:'请在 RViz 为当前地图重新设置 2D Pose Estimate，并核对激光对齐',
  LEASE_EXPIRED:'云端续约中断，小车已取消导航',READY:'地图、定位与导航已就绪',
  NAVIGATION_CHECK_STALE:'导航环境检查超时，控制门已关闭',
};

export function NavigationPage({api,role}:{api:ApiClient;role:string}) {
  const [status,setStatus]=useState<NavigationStatus|null>(null);
  const [error,setError]=useState<string|null>(null);
  const [points,setPoints]=useState([{x:'',y:'',yaw:'0'}]);
  const [cycles,setCycles]=useState('1');
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState<string|null>(null);
  const alive=useRef(true);
  const canMove=role==='OPERATOR';
  const canStop=canMove||role==='MONITOR';
  const mission=!!status?.active || ['ACTIVE','PENDING'].includes(status?.robot?.state??'');

  useEffect(()=>{
    alive.current=true;
    let disposed=false, inFlight=false;
    const refresh=async()=>{
      if(inFlight)return;
      inFlight=true;
      try {
        const next=await api.navigationStatus();
        if(!disposed) {
          setStatus(next);
        }
      }catch(e){if(!disposed)setError(errorMessage(e));}
      finally{inFlight=false;}
    };
    void refresh();
    const timer=window.setInterval(()=>void refresh(),1000);
    return ()=>{disposed=true;alive.current=false;window.clearInterval(timer);};
  },[api]);

  async function start(){
    setError(null);setBusy(true);
    try{
      const parsed=points.map(p=>({x:Number(p.x),y:Number(p.y),yaw:Number(p.yaw)}));
      if(points.some(p=>!p.x.trim()||!p.y.trim()||!p.yaw.trim())||parsed.some(p=>!Object.values(p).every(Number.isFinite)))throw new Error('请填写有效的地图坐标与朝向。');
      if(!status?.robot?.mapId)throw new Error('地图未就绪。');
      await api.startNavigation({mapId:status.robot.mapId,points:parsed,cycles:Number(cycles)});
      if(!alive.current)return;
      setNotice('目标点已发给Broker，等待真实move_base反馈；可切换摄像头查看，巡逻由后端持续续约。');
      const next=await api.navigationStatus();
      if(alive.current)setStatus(next);
    }catch(e){if(alive.current)setError(errorMessage(e));}finally{if(alive.current)setBusy(false);}
  }

  async function cancel(){
    setError(null);setBusy(true);
    try{await api.cancelNavigation();if(!alive.current)return;setNotice('取消和STOP请求已发送，请核对底盘已停止。');const next=await api.navigationStatus();if(alive.current)setStatus(next);}
    catch(e){if(alive.current)setError(errorMessage(e));}
    finally{if(alive.current)setBusy(false);}
  }

  const robot=status?.robot;
  return <div className="stack navigation-page">
    {error&&<ErrorNotice message={error}/>}
    {notice&&<div className="notice notice-info">{notice}</div>}
    <Card title="目标点导航 / 多点巡逻" actions={<StatusPill tone={status?.ready?'ok':'warn'}>{mission?'自主巡逻中':status?.ready?'导航就绪':'尚未就绪'}</StatusPill>}>
      <p className="muted">{REASONS[status?.reason??'']??status?.reason??'正在检查导航能力…'}</p>
      <p className="fine-print">仅使用已有地图的 map 坐标。小车自主规划与避障，切页或关闭浏览器后继续；需结束请主动停止。后端/MQTT失联时3秒租约到期停车。</p>
      <div className="control-readout navigation-readout">
        <div><dt>真实执行状态</dt><dd>{robot?.state??'UNKNOWN'}</dd></div>
        <div><dt>点位进度</dt><dd>{robot?`${robot.pointIndex} / ${robot.totalPoints}`:'—'}</dd></div>
        <div><dt>定位（map，米）</dt><dd>{robot?.x==null||robot?.y==null?'—':`${robot.x.toFixed(2)}, ${robot.y.toFixed(2)}`}</dd></div>
        <div><dt>地图指纹</dt><dd title={robot?.mapId??undefined}>{robot?.mapId?.slice(0,16)??'—'}</dd></div>
      </div>
      <div className="point-list">
        {points.map((point,index)=><div className="point-row" key={index}>
          <span className="point-index">{index+1}</span>
          {(['x','y','yaw'] as const).map(key=><label key={key}>{key==='yaw'?'朝向 rad':`${key.toUpperCase()} m`}
            <input type="number" step="0.1" value={point[key]} aria-label={`目标点${index+1} ${key}`}
              disabled={!canMove||!!mission} onChange={e=>setPoints(old=>old.map((p,i)=>i===index?{...p,[key]:e.target.value}:p))}/></label>)}
          <button className="icon-button" aria-label={`移除目标点${index+1}`} disabled={!canMove||!!mission||points.length===1} onClick={()=>setPoints(old=>old.filter((_,i)=>i!==index))}>×</button>
        </div>)}
      </div>
      <div className="camera-actions">
        <button className="button" disabled={!canMove||!!mission||points.length>=30} onClick={()=>setPoints(old=>[...old,{x:'',y:'',yaw:'0'}])}>添加目标点</button>
        <label className="navigation-cycles">轮次 <input type="number" min="1" max="20" value={cycles} disabled={!canMove||!!mission} onChange={e=>setCycles(e.target.value)}/></label>
        <button className="button primary" disabled={!canMove||!status?.ready||!!mission||busy} onClick={()=>void start()}>开始导航</button>
        <button className="button danger" disabled={!canStop||busy} onClick={()=>void cancel()}>取消导航并停止</button>
      </div>
      {!status?.ready&&<p className="notice notice-info">降级方式：继续使用“控制”页人工遥控，路线/任务继续保存计划。未建立地图、定位和避障前，不执行无感知的定时前进或里程计盲走。</p>}
    </Card>
  </div>;
}
