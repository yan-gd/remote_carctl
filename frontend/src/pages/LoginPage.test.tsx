import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginPage } from './LoginPage';

describe('LoginPage', () => {
  it('requires both fields before calling the API', async () => {
    const onLogin = vi.fn().mockResolvedValue(undefined);
    render(
      <LoginPage onLogin={onLogin} busy={false} error={null} notice={null} />,
    );

    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(screen.getByRole('alert')).toHaveTextContent('请输入用户名和密码。');
    expect(onLogin).not.toHaveBeenCalled();
  });

  it('submits the trimmed username and password', async () => {
    const onLogin = vi.fn().mockResolvedValue(undefined);
    render(
      <LoginPage onLogin={onLogin} busy={false} error={null} notice={null} />,
    );

    await userEvent.type(screen.getByLabelText('用户名'), '  demo_operator  ');
    await userEvent.type(screen.getByLabelText('密码'), 'secret');
    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(onLogin).toHaveBeenCalledWith('demo_operator', 'secret');
  });
});
