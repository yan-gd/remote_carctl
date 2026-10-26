import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import { NavigationPage } from './NavigationPage';
import type { ApiClient } from '../api/client';
import type { MappingState, NavigationStatus } from '../api/types';

vi.mock('../mapping/MapCanvas',()=>({MapCanvas:({onSelect,disabled}:{onSelect:(p:unknown)=>void;disabled:boolean})=>
  <button disabled={disabled} onClick={()=>onSelect({x:1.2345,y:2.3456,yaw:0.3})}>测试地图选点</button>}));

const ready:NavigationStatus={enabled:true,ready:true,reason:'READY',active:false,missionId:null,ownerId:null,
  robot:{ready:true,reason:'READY',frame:'map',mapId:'map-a',state:'IDLE',missionId:null,pointIndex:0,totalPoints:0,x:0,y:0,yaw:0}};
const loaded:MappingState={online:true,pending:null,delivery:null,robot:{session:'s',mode:'NAVIGATION',busy:false,mapId:'map-a',maps:['lab'],
  pose:{x:0,y:0,yaw:0},scan:[],path:[],commandId:null,result:null,message:null}};
function client() {
  return {navigationStatus:vi.fn(async():Promise<NavigationStatus>=>ready),startNavigation:vi.fn(async()=>({missionId:'mission-a',accepted:true,seq:1})),
    cancelNavigation:vi.fn(async()=>({})),mappingStatus:vi.fn(async():Promise<MappingState>=>({online:false,robot:null,pending:null,delivery:null})),
    mappingGrid:vi.fn(async()=>({session:'s',mapId:'map-a',width:4,height:4,resolution:1,originX:0,originY:0,originYaw:0,cells:'AQEBAQ=='})),
    mapCommand:vi.fn(async()=>({id:'c'})),control:vi.fn(async()=>({accepted:true}))};
}
describe('autonomous navigation page',()=>{
  beforeEach(()=>vi.useFakeTimers());
  afterEach(()=>{cleanup();vi.useRealTimers();});
  it('guides initial localization then sends the selected map point directly as one goal',async()=>{
    const api=client();api.mappingStatus.mockResolvedValue(loaded);
    api.navigationStatus.mockResolvedValue({...ready,ready:false,reason:'INITIAL_POSE_REQUIRED'});
    render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});
    expect(screen.getByLabelText('地图操作')).toHaveValue('initial');
    expect(screen.getByText(/先定位小车/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('测试地图选点'));
    await act(async()=>{fireEvent.click(screen.getByText('确认小车初始位置'));});
    expect(api.mapCommand).toHaveBeenCalledWith({action:'INITIAL_POSE',mapId:'map-a',pose:{x:1.2345,y:2.3456,yaw:0.3}});
    expect(api.startNavigation).not.toHaveBeenCalled();
    // The command receipt alone must not declare navigation ready.
    expect(screen.getByRole('button',{name:'开始导航'})).toBeDisabled();
    api.navigationStatus.mockResolvedValue(ready);
    await act(async()=>{await vi.advanceTimersByTimeAsync(1000);});
    expect(screen.getByLabelText('地图操作')).toHaveValue('goal');
    expect(screen.queryByRole('button',{name:'前往此处'})).not.toBeInTheDocument();
    expect(api.startNavigation).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('测试地图选点'));
    await act(async()=>{fireEvent.click(screen.getByText('前往此处'));});
    expect(api.startNavigation).toHaveBeenCalledExactlyOnceWith({mapId:'map-a',cycles:1,points:[{x:1.2345,y:2.3456,yaw:0.3}]});
    expect(screen.getByRole('button',{name:'前往此处'})).toBeDisabled();
  });
  it.each(['map-changed','not-ready','offline','active','server-error'])('rechecks %s at the move click and shows errors beside the map',async condition=>{
    const api=client();api.mappingStatus.mockResolvedValue(loaded);
    render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});fireEvent.click(screen.getByText('测试地图选点'));
    if(condition==='map-changed')api.navigationStatus.mockResolvedValue({...ready,robot:{...ready.robot!,mapId:'other'}});
    if(condition==='not-ready')api.navigationStatus.mockResolvedValue({...ready,ready:false,reason:'LOCALIZATION_UNAVAILABLE'});
    if(condition==='offline')api.mappingStatus.mockResolvedValue({...loaded,online:false});
    if(condition==='active')api.navigationStatus.mockResolvedValue({...ready,active:true});
    if(condition==='server-error')api.startNavigation.mockRejectedValue(new Error('任务提交失败'));
    await act(async()=>{fireEvent.click(screen.getByText('前往此处'));});
    expect(screen.getByRole('alert')).toBeInTheDocument();
    if(condition!=='server-error')expect(api.startNavigation).not.toHaveBeenCalled();
    expect(screen.queryByText('前往目标的任务已提交，等待小车执行反馈。')).not.toBeInTheDocument();
  });
  it('shows readiness failure at the map and never starts automatically when it recovers',async()=>{
    const api=client();api.mappingStatus.mockResolvedValue(loaded);
    api.navigationStatus.mockResolvedValue({...ready,ready:false,reason:'SENSOR_OR_COSTMAP_STALE'});
    render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});fireEvent.click(screen.getByText('测试地图选点'));
    expect(screen.getByText(/暂不能出发：雷达/)).toBeInTheDocument();
    expect(screen.getByText('前往此处')).toBeDisabled();
    api.navigationStatus.mockResolvedValue(ready);
    await act(async()=>{await vi.advanceTimersByTimeAsync(1000);});
    expect(screen.getByText('前往此处')).toBeEnabled();expect(api.startNavigation).not.toHaveBeenCalled();
  });
  it('shows the on-robot spin stop next to the map without restarting the mission',async()=>{
    const api=client();api.mappingStatus.mockResolvedValue(loaded);
    api.navigationStatus.mockResolvedValue({...ready,reason:'NAVIGATION_SPIN_LIMIT',robot:{...ready.robot!,state:'CANCELLED'}});
    render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});
    expect(screen.getByText(/上次导航已停止：小车原地累计转向达到一圈/)).toBeInTheDocument();
    expect(api.startNavigation).not.toHaveBeenCalled();
  });
  it('leaves the backend-owned mission running when switching to the camera or losing focus',async()=>{
    const api=client();
    api.startNavigation.mockImplementation(async()=>{
      api.navigationStatus.mockResolvedValue({...ready,active:true,missionId:'mission-a',ownerId:1});
      return {missionId:'mission-a',accepted:true,seq:1};
    });
    const view=render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});
    fireEvent.change(screen.getByLabelText('目标点1 x'),{target:{value:'1'}});
    fireEvent.change(screen.getByLabelText('目标点1 y'),{target:{value:'2'}});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'开始导航'}));});
    expect(api.startNavigation).toHaveBeenCalledWith({mapId:'map-a',cycles:1,points:[{x:1,y:2,yaw:0}]});
    await act(async()=>{vi.advanceTimersByTime(1000);});
    await act(async()=>{window.dispatchEvent(new Event('blur'));});
    await act(async()=>{vi.advanceTimersByTime(5000);});
    expect(screen.getByRole('button',{name:'开始导航'})).toBeDisabled();
    view.unmount();
    expect(api.cancelNavigation).not.toHaveBeenCalled();
  });
  it('allows a viewer to inspect status but does not expose motion controls',async()=>{
    const api=client();render(<NavigationPage api={api as unknown as ApiClient} role="VIEWER"/>);
    await act(async()=>{});
    expect(screen.queryByRole('button',{name:'开始导航'})).not.toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'取消导航并停止'})).not.toBeInTheDocument();
    expect(api.startNavigation).not.toHaveBeenCalled();
  });
  it('submits one body-relative goal and hides map patrol controls',async()=>{
    const api=client();
    api.navigationStatus.mockResolvedValue({...ready,robot:{...ready.robot!,mode:'relative',frame:'odom',mapId:'relative-session'}});
    const view=render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});
    expect(screen.getByText('附近目标 · 实时避障')).toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'添加目标点'})).not.toBeInTheDocument();
    expect(screen.queryByText('轮次')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('前后距离'),{target:{value:'2'}});
    fireEvent.change(screen.getByLabelText('左右距离'),{target:{value:'1'}});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'开始导航'}));});
    expect(api.startNavigation).toHaveBeenCalledWith({mapId:'relative-session',cycles:1,points:[{x:2,y:1,yaw:0}]});
    view.unmount();
    expect(api.cancelNavigation).not.toHaveBeenCalled();
  });
  it('labels direct odometry mode as having no obstacle avoidance',async()=>{
    const api=client();
    api.navigationStatus.mockResolvedValue({...ready,robot:{...ready.robot!,mode:'relative',frame:'odom',mapId:'direct-session'}});
    render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});
    expect(screen.getByText('附近目标 · 纯里程计（无避障）')).toBeInTheDocument();
    expect(screen.getByText(/可能撞到人或障碍物/)).toBeInTheDocument();
    expect(screen.queryByText('附近目标 · 实时避障')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('前后距离'),{target:{value:'1'}});
    fireEvent.change(screen.getByLabelText('左右距离'),{target:{value:'0'}});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'开始导航'}));});
    expect(api.startNavigation).toHaveBeenCalledWith({mapId:'direct-session',cycles:1,points:[{x:1,y:0,yaw:0}]});
  });
  it('rejects diagonal goals beyond 3m and resets coordinates when mode changes',async()=>{
    const api=client();
    render(<NavigationPage api={api as unknown as ApiClient} role="OPERATOR"/>);
    await act(async()=>{});
    fireEvent.change(screen.getByLabelText('目标点1 x'),{target:{value:'100'}});
    api.navigationStatus.mockResolvedValue({...ready,robot:{...ready.robot!,mode:'relative',frame:'odom',mapId:'relative-session'}});
    await act(async()=>{await vi.advanceTimersByTimeAsync(1000);});
    expect(screen.getByLabelText('前后距离')).toHaveValue(null);
    fireEvent.change(screen.getByLabelText('前后距离'),{target:{value:'3'}});
    fireEvent.change(screen.getByLabelText('左右距离'),{target:{value:'3'}});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'开始导航'}));});
    expect(screen.getByRole('alert')).toHaveTextContent('0.1至3米');
    expect(api.startNavigation).not.toHaveBeenCalled();
  });
});
