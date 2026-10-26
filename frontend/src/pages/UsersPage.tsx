import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import type { ApiClient } from '../api/client';
import type { UserView } from '../api/types';
import { CAPABILITIES, ROLE_DEFAULTS } from '../auth/permissions';
import { Card, Empty, ErrorNotice, StatusPill, errorMessage } from '../components/UI';
import { formatDateTime, roleLabel } from '../utils/format';

const ROLES = ['OPERATOR', 'MONITOR', 'MAINTAINER', 'VIEWER'];

export function UsersPage({api}: {api: ApiClient}) {
  const [users,setUsers]=useState<UserView[]>([]);
  const [query,setQuery]=useState('');
  const [selected,setSelected]=useState<number|null>(null);
  const [username,setUsername]=useState('');
  const [password,setPassword]=useState('');
  const [role,setRole]=useState('VIEWER');
  const [grants,setGrants]=useState<string[]>(ROLE_DEFAULTS.VIEWER);
  const [error,setError]=useState<string|null>(null);
  const [notice,setNotice]=useState<string|null>(null);
  const [busy,setBusy]=useState(false);

  const load=useCallback(async()=>{
    try {setUsers(await api.users());setError(null);}
    catch(e){setError(errorMessage(e));}
  },[api]);
  useEffect(()=>{void load();},[load]);
  const visible=useMemo(()=>users.filter(u=>
    `${u.username} ${u.role} ${roleLabel(u.role)}`.toLowerCase().includes(query.trim().toLowerCase())),[users,query]);

  function choose(user?:UserView){
    setSelected(user?.id??null);
    setUsername(user?.username??'');setPassword('');
    setRole(user?.role??'VIEWER');
    setGrants(user?.permissions??ROLE_DEFAULTS[user?.role??'VIEWER']??[]);
    setError(null);setNotice(null);
  }

  function chooseRole(value:string){setRole(value);setGrants([...(ROLE_DEFAULTS[value]??[])]);}
  function toggle(value:string,checked:boolean){setGrants(old=>{
    if(!checked&&value==='STOP')return old.filter(x=>!['STOP','MOVE','MAP_EDIT','NAVIGATE'].includes(x));
    if(checked&&['MOVE','MAP_EDIT','NAVIGATE'].includes(value))return Array.from(new Set([...old,'STOP',value]));
    return checked?[...old,value]:old.filter(x=>x!==value);
  });}

  async function save(e:FormEvent){
    e.preventDefault();if(busy)return;
    setBusy(true);setError(null);setNotice(null);
    try{
      if(selected===null){
        if(!/^[A-Za-z0-9_]{3,32}$/.test(username)||!(/^[!-~]{8,72}$/.test(password)))
          throw new Error('用户名需3–32位字母数字/下划线；密码需8–72位非空格字符。');
        await api.createUser({username,password,role,permissions:grants});
      } else await api.updateUser(selected,{role,permissions:grants,...(password?{password}:{})});
      const message=selected===null?'账号已创建（如用户名曾停用则恢复原账号）。':'权限已保存，立即对后续请求生效。';
      choose();setNotice(message);await load();
    }catch(e){setError(errorMessage(e));}
    finally{setBusy(false);}
  }

  async function disable(user:UserView){
    if(busy||user.role==='ADMIN'||!window.confirm(`停用 ${user.username}？其登录令牌将失效，历史日志仍保留。`))return;
    setBusy(true);setError(null);setNotice(null);
    try{await api.deleteUser(user.id);setNotice(`${user.username} 已停用。`);if(selected===user.id)choose();await load();}
    catch(e){setError(errorMessage(e));}
    finally{setBusy(false);}
  }

  return <div className="stack users-page">
    <Card title="用户管理" actions={<button className="button primary small" onClick={()=>choose()}>新增账号</button>}>
      <div className="data-toolbar">
        <input aria-label="搜索用户" placeholder="搜索用户名或角色" value={query} onChange={e=>setQuery(e.target.value)}/>
        <span className="fine-print">{visible.length} 个账号</span>
      </div>
      {notice&&<div className="notice notice-info" role="status">{notice}</div>}
      {error&&<ErrorNotice message={error}/>}
      <div className="table-wrap">
        <table className="data-table">
          <thead><tr><th>账号</th><th>角色</th><th>权限数</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody>{visible.map(u=><tr key={u.id}>
            <td className="strong-cell">{u.username}</td>
            <td>{roleLabel(u.role)}</td><td>{u.permissions?.length??0}</td>
            <td><StatusPill tone={u.active===false?'warn':'ok'}>{u.active===false?'已停用':'正常'}</StatusPill></td>
            <td>{formatDateTime(u.createdAt)}</td>
            <td><div className="row-actions">
              {u.role==='ADMIN'?<span className="fine-print">受保护</span>:u.active===false?
                <span className="fine-print">新增同名账号可恢复</span>:<>
                <button className="button ghost small" onClick={()=>choose(u)}>编辑权限</button>
                <button className="button danger small" disabled={busy} onClick={()=>void disable(u)}>停用</button></>}
            </div></td>
          </tr>)}</tbody>
        </table>
        {visible.length===0&&<Empty>未找到账号</Empty>}
      </div>
    </Card>
    <Card title={selected===null?'新增账号':'编辑用户权限'} actions={selected!==null&&<button className="button ghost small" onClick={()=>choose()}>取消编辑</button>}>
      <form onSubmit={e=>void save(e)} className="user-form">
        <div className="user-form-fields">
          <label>用户名<input value={username} disabled={selected!==null||busy} onChange={e=>setUsername(e.target.value)} placeholder="例如 operator_02" required/></label>
          <label>{selected===null?'初始密码':'重设密码（可留空）'}<input type="password" autoComplete="new-password" value={password} onChange={e=>setPassword(e.target.value)} minLength={8} maxLength={72} required={selected===null} disabled={busy} placeholder={selected===null?'至少8位':'不修改请留空'}/></label>
          <label>业务角色<select value={role} disabled={busy} onChange={e=>chooseRole(e.target.value)}>{ROLES.map(r=><option key={r} value={r}>{roleLabel(r)}</option>)}</select></label>
        </div>
        <div className="permission-head"><strong>功能权限</strong><span className="fine-print">勾选后由服务端校验，不受原角色默认权限限制</span></div>
        <div className="permission-grid">{CAPABILITIES.map(p=><label key={p.id} className="permission-item">
          <input type="checkbox" checked={grants.includes(p.id)} disabled={busy} onChange={e=>toggle(p.id,e.target.checked)}/>{p.label}
        </label>)}</div>
        <div className="user-form-actions"><button type="submit" className="button primary" disabled={busy}>{busy?'保存中…':selected===null?'创建账号':'保存修改'}</button></div>
      </form>
    </Card>
  </div>;
}
