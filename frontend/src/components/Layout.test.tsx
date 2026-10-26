import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, act } from '@testing-library/react';
import { Shell } from './Layout';

vi.mock('../robot/RobotContext',()=>({useRobot:()=>({status:null,info:null,error:null,updatedAt:null})}));
afterEach(cleanup);
describe('global robot stop',()=>{
  it.each(['OPERATOR','MONITOR','MAINTAINER','VIEWER'])('shows only authorized entries for %s and removes inspection',role=>{
    render(<Shell username="test" role={role} active="dashboard" onNavigate={()=>{}} onLogout={async()=>{}} onStop={async()=>{}}>Content</Shell>);
    expect(screen.queryByRole('button',{name:'巡检'})).not.toBeInTheDocument();
    expect(!!screen.queryByRole('button',{name:'控制'})).toBe(role==='OPERATOR');
    expect(!!screen.queryByRole('button',{name:'立即停止'})).toBe(['OPERATOR','MONITOR'].includes(role));
    expect(screen.getByRole('button',{name:'告警'})).toBeInTheDocument();
  });
  it('remains available on the camera page, including when status is offline',async()=>{
    const stop=vi.fn(async()=>undefined);
    render(<Shell username="operator" role="OPERATOR" active="camera" onNavigate={()=>undefined}
      onLogout={async()=>undefined} onStop={stop}><div>Camera</div></Shell>);
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'立即停止'}));});
    expect(stop).toHaveBeenCalledOnce();
    expect(screen.getByRole('status')).toHaveTextContent('STOP已发送');
  });
  it('surfaces a failed stop request and does not grant viewer motion permissions',async()=>{
    const stop=vi.fn(async()=>{throw new Error('MQTT不可用');});
    const view=render(<Shell username="monitor" role="MONITOR" active="navigation" onNavigate={()=>undefined}
      onLogout={async()=>undefined} onStop={stop}><div>Navigation</div></Shell>);
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'立即停止'}));});
    expect(screen.getByRole('alert')).toHaveTextContent('MQTT不可用');
    view.unmount();
    render(<Shell username="viewer" role="VIEWER" active="camera" onNavigate={()=>undefined}
      onLogout={async()=>undefined} onStop={stop}><div>Camera</div></Shell>);
    expect(screen.queryByRole('button',{name:'立即停止'})).not.toBeInTheDocument();
  });
});
