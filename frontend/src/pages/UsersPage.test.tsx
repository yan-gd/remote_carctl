import {afterEach,describe,expect,it,vi} from 'vitest';
import {act,cleanup,fireEvent,render,screen} from '@testing-library/react';
import {UsersPage} from './UsersPage';
import type {ApiClient} from '../api/client';
import type {UserView} from '../api/types';

const admin:UserView={id:1,username:'admin',role:'ADMIN',active:true,permissions:['MOVE','STOP','NAVIGATE']};
const operator:UserView={id:2,username:'demo_operator',role:'OPERATOR',active:true,permissions:['MOVE','STOP','NAVIGATE']};
function client(){return {
  users:vi.fn(async()=>[admin,operator]),createUser:vi.fn(async()=>operator),
  updateUser:vi.fn(async()=>operator),deleteUser:vi.fn(async()=>undefined),
};}

describe('administrator users screen',()=>{
  afterEach(cleanup);
  it('protects admin, supports search, and updates explicit grants',async()=>{
    const api=client();render(<UsersPage api={api as unknown as ApiClient}/>);await act(async()=>{});
    expect(screen.getByText('受保护')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('搜索用户'),{target:{value:'demo'}});
    expect(screen.queryByText('受保护')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('编辑权限'));
    expect(screen.getByLabelText('手动驾驶')).toBeChecked();
    fireEvent.click(screen.getByLabelText('手动驾驶'));
    fireEvent.change(screen.getByLabelText('业务角色'),{target:{value:'MONITOR'}});
    expect(screen.getByLabelText('确认告警')).toBeChecked();
    await act(async()=>{fireEvent.click(screen.getByText('保存修改'));});
    expect(api.updateUser).toHaveBeenCalledWith(2,expect.objectContaining({role:'MONITOR',permissions:['STOP','ALARM_ACK']}));
  });

  it('creates a user without saving the password to the displayed list',async()=>{
    const api=client();render(<UsersPage api={api as unknown as ApiClient}/>);await act(async()=>{});
    fireEvent.change(screen.getByLabelText('用户名'),{target:{value:'new_monitor'}});
    fireEvent.change(screen.getByLabelText('初始密码'),{target:{value:'TestPassword123!'}});
    fireEvent.change(screen.getByLabelText('业务角色'),{target:{value:'MONITOR'}});
    await act(async()=>{fireEvent.click(screen.getByText('创建账号'));});
    expect(api.createUser).toHaveBeenCalledWith({username:'new_monitor',password:'TestPassword123!',role:'MONITOR',permissions:['STOP','ALARM_ACK']});
    expect(screen.queryByText('TestPassword123!')).not.toBeInTheDocument();
  });
});
