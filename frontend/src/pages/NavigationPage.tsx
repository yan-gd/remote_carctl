import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { NavigationStatus } from '../api/types';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';
import { MappingPanel } from '../mapping/MappingPanel';

const REASONS: Record<string,string> = {
  NAVIGATION_DISABLED:'后端导航尚未启用',NO_LIVE_NAVIGATION_REPORT:'未收到新鲜导航报告',MAP_UNAVAILABLE:'没有可用的map地图',
  SENSOR_OR_COSTMAP_STALE:'雷达、底盘或局部代价地图数据缺失/过期',MOVE_BASE_UNAVAILABLE:'move_base没有启动',
  LOCALIZATION_UNAVAILABLE:'map → base_link定位不可用',DIRECT_CMD_VEL_PUBLISHER:'检测到其他节点直接控制底盘，请先隔离导航速度话题',
  INITIAL_POSE_REQUIRED:'请在地图工作台设置小车初始位置和朝向，并核对激光对齐',
  WEB_MAPPING_ACTIVE:'正在建图，保存并加载地图后可导航',WEB_MAP_NOT_LOADED:'请先在地图工作台加载已保存地图',
  LEASE_EXPIRED:'云端续约中断，小车已取消导航',READY:'地图、定位与导航已就绪',
  NAVIGATION_CHECK_STALE:'导航环境检查超时，控制门已关闭',
  LIVE_GRID_UNAVAILABLE:'等待实时激光障碍地图，无需加载预存地图',
  ROBOT_MUST_BE_STOPPED:'请先停车，再设置相对目标',
  RELATIVE_GOAL_OUT_OF_RANGE:'目标直线距离须为0.1至3米',
  RELATIVE_SINGLE_GOAL_ONLY:'相对导航仅支持单个目标、执行一次',
  RELATIVE_TRAVEL_LIMIT:'绕行距离达到限制，已停止',
  GOAL_NOT_IN_FREE_MAP_SPACE:'目标或周围空间未被确认可通行，请选择雷达可见的空闲区域',
  CHASSIS_UNAVAILABLE:'底盘连接或里程计状态异常，已禁止导航',
  ODOM_GOAL_REACHED:'里程计已达到目标误差范围（请现场确认实际位置）',
  ODOM_RESULT_4:'纯里程计目标执行失败，已停车',
  NAVIGATION_MODE_MISMATCH:'导航模式不一致，请更新服务器并刷新页面',
  NAVIGATION_HEALTH_LOST:'传感器、定位或导航参考失效，已停止',
  GOAL_TIMEOUT:'目标执行超时，已停止',
  MOVE_BASE_RESULT_4:'没有找到可行路径或无法继续行驶，导航已终止',
  MOVE_BASE_SUCCEEDED:'已收到 move_base 到达反馈',
};

export function NavigationPage({api,role}:{api:ApiClient;role:string}) {
  const [status,setStatus]=useState<NavigationStatus|null>(null);
  const [error,setError]=useState<string|null>(null);
  const [points,setPoints]=useState([{x:'',y:'',yaw:'0'}]);
  const [cycles,setCycles]=useState('1');
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState<string|null>(null);
  const goalReference=useRef<string|null>(null);
  const alive=useRef(true);
  const canMove=role==='OPERATOR';
  const canStop=canMove||role==='MONITOR';
  const mission=!!status?.active || ['ACTIVE','PENDING'].includes(status?.robot?.state??'');
  const relative=status?.robot?.mode==='relative';
  const direct=relative&&!!status?.robot?.mapId?.startsWith('direct-');
  const mode=relative?'relative':'map';

  useEffect(()=>{
    // Coordinates from the other mode must never be reinterpreted as a new goal.
    setPoints([{x:'',y:'',yaw:'0'}]);setCycles('1');setNotice(null);
    goalReference.current=null;
  },[mode,status?.robot?.mapId]);

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
      if(points.some(p=>!p.x.trim()||!p.y.trim()||!p.yaw.trim())||parsed.some(p=>!Object.values(p).every(Number.isFinite)))throw new Error('请填写有效的目标坐标或距离与朝向。');
      if(relative&&(parsed.length!==1||Math.hypot(parsed[0].x,parsed[0].y)<0.1||Math.hypot(parsed[0].x,parsed[0].y)>3))
        throw new Error('相对目标直线距离必须在0.1至3米内，且只允许一个目标。');
      if(parsed.some(p=>Math.abs(p.yaw)>Math.PI))throw new Error('朝向必须在−π至π弧度内。');
      if(!status?.robot?.mapId)throw new Error('导航参考尚未就绪。');
      if(goalReference.current&&goalReference.current!==status.robot.mapId)throw new Error('地图已经变化，请重新选择目标。');
      await api.startNavigation({mapId:status.robot.mapId,points:parsed,cycles:relative?1:Number(cycles)});
      if(!alive.current)return;
      setNotice(direct?'纯里程计目标已发给小车；无自动避障，必须在清空障碍的场地由现场人员监护。':relative?'相对目标已发给Broker，等待小车确认；开始时的位置和朝向会被固定，可切换摄像头查看。':'目标点已发给Broker，等待真实move_base反馈；可切换摄像头查看，巡逻由后端持续续约。');
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
  const reason=direct&&status?.reason==='READY'?'里程计和底盘已就绪（没有自动避障）':
    relative&&status?.reason==='READY'?'实时激光障碍地图与里程计已就绪':
    relative&&status?.reason==='LOCALIZATION_UNAVAILABLE'?'odom → base_link里程计位姿不可用':
    REASONS[status?.reason??'']??status?.reason??'正在检查导航能力…';
  return <div className={`stack navigation-page${relative?' is-relative':''}`}>
    {error&&<ErrorNotice message={error}/>}
    {notice&&<div className="notice notice-info">{notice}</div>}
    {!relative&&<MappingPanel api={api} role={role} mission={mission} onReference={id=>{
      if(goalReference.current&&goalReference.current!==id){goalReference.current=null;setPoints([{x:'',y:'',yaw:'0'}]);}
    }} onGoal={(pose,id)=>{
      if(id!==status?.robot?.mapId){setError('地图状态正在同步，请稍后重新选择。');return;}
      goalReference.current=id;
      const point={x:pose.x.toFixed(3),y:pose.y.toFixed(3),yaw:String(pose.yaw)};
      setPoints(old=>old.length===1&&!old[0].x&&!old[0].y?[point]:old.length<30?[...old,point]:old);
    }}/>}
    <Card title={direct?'附近目标 · 纯里程计（无避障）':relative?'附近目标 · 实时避障':'目标点导航 / 多点巡逻'} actions={<StatusPill tone={status?.ready?'ok':'warn'}>{mission?(relative?'正在前往目标':'自主巡逻中'):status?.ready?'导航就绪':'尚未就绪'}</StatusPill>}>
      <p className="muted">{reason}</p>
      {direct&&<p className="notice notice-error">此模式没有自动避障能力：可能撞到人或障碍物。仅在清空行驶路径、现场有人监护且能立即急停时使用。</p>}
      <p className="fine-print">{direct?'以收到目标时的里程计位姿为起点，前方为X正方向、左侧为Y正方向。小车先转向再前进，目标距离0.1至3米，无雷达安全检查。':relative?'以小车接受目标时的位置和朝向为起点，前方为X正方向、左侧为Y正方向。仅单点，直线距离0.1至3米；无需预存地图，使用实时激光障碍数据。':'仅使用已有地图的 map 坐标。小车自主规划与避障。'}切页或关闭浏览器后继续；需结束请主动停止。后端/MQTT失联时3秒租约到期停车。</p>
      <div className="control-readout navigation-readout">
        <div><dt>真实执行状态</dt><dd>{robot?.state??'UNKNOWN'}</dd></div>
        <div><dt>点位进度</dt><dd>{robot?`${robot.pointIndex} / ${robot.totalPoints}`:'—'}</dd></div>
        <div><dt>{relative?'里程计位置（odom，米）':'定位（map，米）'}</dt><dd>{robot?.x==null||robot?.y==null?'—':`${robot.x.toFixed(2)}, ${robot.y.toFixed(2)}`}</dd></div>
        <div><dt>{relative?'导航会话':'地图指纹'}</dt><dd title={robot?.mapId??undefined}>{robot?.mapId?.slice(0,16)??'—'}</dd></div>
      </div>
      <div className="point-list">
        {points.map((point,index)=><div className={`point-row${relative?' relative-goal-row':''}`} key={index}>
          <span className="point-index">{index+1}</span>
          {(['x','y','yaw'] as const).map(key=><label key={key}>{relative?({x:'前后 m（前＋ / 后−）',y:'左右 m（左＋ / 右−）',yaw:'相对朝向 rad'})[key]:key==='yaw'?'朝向 rad':`${key.toUpperCase()} m`}
            <input type="number" step="0.1" min={relative?(key==='yaw'?-Math.PI:-3):undefined}
              max={relative?(key==='yaw'?Math.PI:3):undefined} value={point[key]}
              aria-label={relative?({x:'前后距离',y:'左右距离',yaw:'相对朝向'})[key]:`目标点${index+1} ${key}`}
              disabled={!canMove||!!mission} onChange={e=>setPoints(old=>old.map((p,i)=>i===index?{...p,[key]:e.target.value}:p))}/></label>)}
          {!relative&&<button className="icon-button" aria-label={`移除目标点${index+1}`} disabled={!canMove||!!mission||points.length===1} onClick={()=>setPoints(old=>old.filter((_,i)=>i!==index))}>×</button>}
        </div>)}
      </div>
      <div className="camera-actions">
        {!relative&&<><button className="button" disabled={!canMove||!!mission||points.length>=30} onClick={()=>setPoints(old=>[...old,{x:'',y:'',yaw:'0'}])}>添加目标点</button>
        <label className="navigation-cycles">轮次 <input type="number" min="1" max="20" value={cycles} disabled={!canMove||!!mission} onChange={e=>setCycles(e.target.value)}/></label></>}
        <button className="button primary" disabled={!canMove||!status?.ready||!!mission||busy} onClick={()=>void start()}>开始导航</button>
        <button className="button danger" disabled={!canStop||busy} onClick={()=>void cancel()}>取消导航并停止</button>
      </div>
      {relative&&<p className="fine-print">例如前方2米、左侧1米：填写 X=2、Y=1、朝向=0。{direct?'直接按里程计转向及前进，不绕开障碍；位置丢失或超时会停车，最多执行60秒。':'雷达未观测区域或目标周围有障碍时会拒绝；无法绕行会终止，最多执行60秒。'}</p>}
      {!status?.ready&&<p className="notice notice-info">{direct?'请检查底盘、里程计定位及速度话题是否正常；本模式不需要雷达。':relative?'请保持底盘、雷达及相对导航进程运行，等待实时数据就绪；也可以继续人工遥控。':'可继续使用“控制”页人工遥控；地图、定位和避障条件就绪后再启动导航。'}</p>}
    </Card>
  </div>;
}
