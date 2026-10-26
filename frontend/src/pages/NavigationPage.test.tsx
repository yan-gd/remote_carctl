import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import { NavigationPage } from './NavigationPage';
import type { ApiClient } from '../api/client';
import type { NavigationStatus } from '../api/types';

const ready:NavigationStatus={enabled:true,ready:true,reason:'READY',active:false,missionId:null,ownerId:null,
  robot:{ready:true,reason:'READY',frame:'map',mapId:'map-a',state:'IDLE',missionId:null,pointIndex:0,totalPoints:0,x:0,y:0,yaw:0}};
function client() {
  return {navigationStatus:vi.fn(async():Promise<NavigationStatus>=>ready),startNavigation:vi.fn(async()=>({missionId:'mission-a',accepted:true,seq:1})),
    cancelNavigation:vi.fn(async()=>({})),mappingStatus:vi.fn(async()=>({online:false,robot:null,pending:null,delivery:null}))};
}
describe('autonomous navigation page',()=>{
  beforeEach(()=>vi.useFakeTimers());
  afterEach(()=>{cleanup();vi.useRealTimers();});
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
    expect(screen.getByRole('button',{name:'开始导航'})).toBeDisabled();
    expect(screen.getByRole('button',{name:'取消导航并停止'})).toBeDisabled();
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
