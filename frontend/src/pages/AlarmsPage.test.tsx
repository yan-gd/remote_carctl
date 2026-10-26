import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest';
import {render,screen,fireEvent,act,cleanup} from '@testing-library/react';
import {AlarmsPage} from './AlarmsPage';
import {ApiClient,ApiError} from '../api/client';
import type {AlarmItem} from '../api/types';
const item:AlarmItem={id:7,title:'导航前方检测到近距离障碍',description:'前方0.5m',source:'SCOUT-001:NAV_OBSTACLE',level:'WARNING',status:'UNACKNOWLEDGED',occurredAt:'2026-10-26T00:00:00Z',acknowledgedAt:null,acknowledgedBy:null};
const acknowledged={...item,status:'ACKNOWLEDGED',acknowledgedBy:2,acknowledgedAt:'2026-10-26T01:00:00Z'};
function client(){return {alarms:vi.fn(async()=>({items:[item],total:1,page:1,size:20})),acknowledgeAlarm:vi.fn(async()=>acknowledged)};}
describe('alarm processing',()=>{
  beforeEach(()=>vi.useFakeTimers());afterEach(()=>{cleanup();vi.useRealTimers();});
  it.each(['OPERATOR','MAINTAINER','VIEWER'])('keeps alarm actions read-only for %s',async role=>{
    const api=client();render(<AlarmsPage api={api as unknown as ApiClient} role={role}/>);await act(async()=>{});
    expect(screen.getByRole('columnheader',{name:'告警内容'})).toBeInTheDocument();
    expect(screen.queryByRole('button',{name:'确认'})).not.toBeInTheDocument();expect(api.acknowledgeAlarm).not.toHaveBeenCalled();
  });
  it('monitor acknowledges and sees persistent actor/time without pretending fault recovery',async()=>{
    const api=client();api.acknowledgeAlarm.mockImplementation(async()=>{api.alarms.mockResolvedValue({items:[acknowledged],total:1,page:1,size:20});return acknowledged;});
    render(<AlarmsPage api={api as unknown as ApiClient} role="MONITOR"/>);await act(async()=>{});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'确认'}));});
    expect(api.acknowledgeAlarm).toHaveBeenCalledExactlyOnceWith(7);
    fireEvent.click(screen.getByText('查看详情'));
    expect(screen.getByText(/确认人 #2/)).toBeInTheDocument();expect(screen.getByRole('status')).toHaveTextContent('故障状态请以实时数据为准');
    expect(screen.queryByRole('button',{name:'确认'})).not.toBeInTheDocument();
  });
  it('polls new alarms and applies time filters',async()=>{
    const api=client();api.alarms.mockResolvedValueOnce({items:[],total:0,page:1,size:20});
    render(<AlarmsPage api={api as unknown as ApiClient} role="MONITOR"/>);await act(async()=>{});
    expect(screen.getByText('没有符合条件的告警。')).toBeInTheDocument();
    await act(async()=>{await vi.advanceTimersByTimeAsync(3000);});
    expect(screen.getByText(item.title)).toBeInTheDocument();
    await act(async()=>{fireEvent.change(screen.getByLabelText('开始时间'),{target:{value:'2026-10-25T12:00'}});});
    expect(api.alarms).toHaveBeenLastCalledWith(expect.objectContaining({from:new Date('2026-10-25T12:00').toISOString(),page:1}));
    await act(async()=>{fireEvent.change(screen.getByLabelText('结束时间'),{target:{value:'2026-10-24T12:00'}});});
    expect(screen.getByRole('alert')).toHaveTextContent('开始时间不能晚于结束时间');
  });
  it('searches server-side across all pages',async()=>{
    const api=client();render(<AlarmsPage api={api as unknown as ApiClient} role="MONITOR"/>);await act(async()=>{});
    fireEvent.change(screen.getByLabelText('搜索告警'),{target:{value:'NAV_OBSTACLE'}});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'搜索'}));});
    expect(api.alarms).toHaveBeenLastCalledWith(expect.objectContaining({q:'NAV_OBSTACLE',page:1}));
  });
  it('recovers when another monitor has already acknowledged',async()=>{
    const api=client();api.acknowledgeAlarm.mockImplementation(async()=>{api.alarms.mockResolvedValue({items:[acknowledged],total:1,page:1,size:20});throw new ApiError(409,'BUSINESS_CONFLICT','已确认');});
    render(<AlarmsPage api={api as unknown as ApiClient} role="MONITOR"/>);await act(async()=>{});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'确认'}));});
    fireEvent.click(screen.getByText('查看详情'));
    expect(screen.getByRole('status')).toHaveTextContent('其他值班员');expect(screen.getByText(/确认人 #2/)).toBeInTheDocument();
  });
});
