import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {act,fireEvent,render,screen,cleanup} from '@testing-library/react';
import {MappingPanel as Panel} from './MappingPanel';
import type {ComponentProps} from 'react';
import type {ApiClient} from '../api/client';
import type {MappingState} from '../api/types';

// Coordinate projection and actual canvas rendering have separate tests/browser verification.
vi.mock('./MapCanvas',()=>({MapCanvas:({onSelect,disabled}:{onSelect:(p:unknown)=>void;disabled:boolean})=>
  <button disabled={disabled} onClick={()=>onSelect({x:1,y:2,yaw:0.3})}>测试地图选点</button>}));
const state:MappingState={online:true,pending:null,delivery:null,robot:{session:'s',mode:'NAVIGATION',busy:false,mapId:'m',maps:['lab'],
  pose:{x:0,y:0,yaw:0},scan:[],path:[],commandId:null,result:null,message:null}};
const navigation={enabled:true,ready:true,reason:'READY',active:false,missionId:null,ownerId:null,
  robot:{ready:true,reason:'READY',frame:'map',mapId:'m',state:'IDLE',missionId:null,pointIndex:0,totalPoints:0,x:0,y:0,yaw:0}};
function MappingPanel(props:Omit<ComponentProps<typeof Panel>,'navigation'|'navigationReason'|'navigationBusy'|'onNavigate'>&Partial<ComponentProps<typeof Panel>>){
  return <Panel navigation={navigation} navigationReason="导航已就绪" navigationBusy={false} onNavigate={async()=>{}} {...props}/>;
}
function client(){return {mappingStatus:vi.fn(async()=>state),mappingGrid:vi.fn(async()=>({session:'s',mapId:'m',width:2,height:2,resolution:1,originX:0,originY:0,originYaw:0,cells:'AQEBAQ=='})),
  mapCommand:vi.fn(async()=>({id:'c'})),control:vi.fn(async()=>({accepted:true}))};}
describe('Web mapping operations',()=>{
  beforeEach(()=>vi.useFakeTimers());afterEach(()=>{cleanup();vi.useRealTimers();});
  it('moves to a selected goal only on explicit click; patrol and initial position are separate',async()=>{
    const api=client(),goal=vi.fn(),navigate=vi.fn(async()=>{});render(<MappingPanel api={api as unknown as ApiClient} role="OPERATOR" mission={false} onGoal={goal} onNavigate={navigate} onReference={()=>{}}/>);
    await act(async()=>{});fireEvent.click(screen.getByText('测试地图选点'));
    expect(api.mapCommand).not.toHaveBeenCalled();expect(api.control).not.toHaveBeenCalled();expect(navigate).not.toHaveBeenCalled();
    await act(async()=>{fireEvent.click(screen.getByText('前往此处'));});
    expect(navigate).toHaveBeenCalledWith({x:1,y:2,yaw:0.3},'m');expect(goal).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('地图操作'),{target:{value:'patrol'}});
    fireEvent.click(screen.getByText('测试地图选点'));
    fireEvent.click(screen.getByText('加入巡逻点'));expect(goal).toHaveBeenCalledWith({x:1,y:2,yaw:0.3},'m');
    fireEvent.change(screen.getByLabelText('地图操作'),{target:{value:'initial'}});
    fireEvent.click(screen.getByText('测试地图选点'));
    await act(async()=>{fireEvent.click(screen.getByText('确认小车初始位置'));});
    expect(api.mapCommand).toHaveBeenCalledWith({action:'INITIAL_POSE',mapId:'m',pose:{x:1,y:2,yaw:0.3}});
  });
  it('viewers cannot mutate mapping or stage destinations',async()=>{
    const api=client();render(<MappingPanel api={api as unknown as ApiClient} role="VIEWER" mission={false} onGoal={()=>{}} onReference={()=>{}}/>);
    await act(async()=>{});expect(screen.queryByText('开始新建地图')).not.toBeInTheDocument();expect(screen.getByText('测试地图选点')).toBeDisabled();
    expect(api.mapCommand).not.toHaveBeenCalled();
  });
  it('a lost link clears selected coordinates and disables mutations',async()=>{
    const api=client(),reference=vi.fn();render(<MappingPanel api={api as unknown as ApiClient} role="OPERATOR" mission={false} onGoal={()=>{}} onReference={reference}/>);
    await act(async()=>{});fireEvent.click(screen.getByText('测试地图选点'));
    api.mappingStatus.mockRejectedValue(new Error('断线'));
    await act(async()=>{await vi.advanceTimersByTimeAsync(1000);});
    expect(reference).toHaveBeenLastCalledWith(null);expect(screen.getByText('开始新建地图')).toBeDisabled();
    expect(screen.queryByText('前往此处')).not.toBeInTheDocument();
  });
  it('manual mapping control releases on blur while the map remains visible',async()=>{
    const api=client();api.mappingStatus.mockResolvedValue({...state,robot:{...state.robot!,mode:'MAPPING'}});
    render(<MappingPanel api={api as unknown as ApiClient} role="OPERATOR" mission={false} onGoal={()=>{}} onReference={()=>{}}/>);
    await act(async()=>{});
    await act(async()=>{fireEvent.keyDown(screen.getByLabelText('建图前进'),{key:'Enter'});});
    expect(api.control).toHaveBeenCalledWith('FORWARD');
    await act(async()=>{fireEvent(window,new Event('blur'));});
    expect(api.control).toHaveBeenLastCalledWith('STOP');expect(screen.getByText('测试地图选点')).toBeInTheDocument();
  });
});
