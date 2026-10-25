import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import { NavigationPage } from './NavigationPage';
import type { ApiClient } from '../api/client';
import type { NavigationStatus } from '../api/types';

const ready:NavigationStatus={enabled:true,ready:true,reason:'READY',active:false,missionId:null,ownerId:null,
  robot:{ready:true,reason:'READY',frame:'map',mapId:'map-a',state:'IDLE',missionId:null,pointIndex:0,totalPoints:0,x:0,y:0,yaw:0}};
function client() {
  return {navigationStatus:vi.fn(async():Promise<NavigationStatus>=>ready),startNavigation:vi.fn(async()=>({missionId:'mission-a',accepted:true,seq:1})),
    cancelNavigation:vi.fn(async()=>({}))};
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
});
