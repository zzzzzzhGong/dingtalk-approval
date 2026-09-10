/**
 * 我的待办页面逻辑。
 *
 * 职责：
 *  1. 拉取指派给当前用户的钉钉审批任务；
 *  2. 渲染审批表单内容与已有审批意见；
 *  3. 提交「同意 / 拒绝 / 补充意见」，并在成功后刷新列表。
 *
 * 依赖后端接口：
 *  GET  /api/dingtalk/csrf                        获取 CSRF 令牌
 *  GET  /api/dingtalk/session                     登录态与权限
 *  GET  /api/dingtalk/approvals/todo              我的待办列表
 *  POST /api/dingtalk/approvals/{id}/decision     同意 / 拒绝
 *  POST /api/dingtalk/approvals/{id}/comments     补充意见
 */
(function () {
  'use strict';

  /** 状态码到中文展示文案。 */
  const STATUS_LABELS = {
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    RUNNING: '审批中',
    TERMINATED: '已撤销/终止'
  };

  /** 本条审批意见的动作文案。 */
  const ACTION_LABELS = {
    AGREE: '同意',
    REFUSE: '拒绝',
    COMMENT: '补充意见'
  };

  const dom = {
    list: document.querySelector('#todoList'),
    stats: document.querySelector('#stats'),
    message: document.querySelector('#message'),
    reload: document.querySelector('#reload'),
    loginPrompt: document.querySelector('#loginPrompt')
  };

  let csrfToken = '';

  /** 转义 HTML，防止审批表单内容注入页面。 */
  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, (char) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[char]));
  }

  /** 统一的接口调用，自动带上会话与 CSRF 令牌。 */
  async function api(url, options = {}) {
    const response = await fetch(url, {
      credentials: 'same-origin',
      ...options,
      headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {}), ...options.headers }
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok || data.success === false) {
      throw new Error(data.message || '请求失败，请稍后重试。');
    }
    return data;
  }

  function setMessage(text, type = '') {
    dom.message.textContent = text || '';
    dom.message.className = 'notice ' + type;
  }

  function formatDate(value) {
    return value ? new Date(value).toLocaleString() : '-';
  }

  /** 渲染顶部统计卡片。 */
  function renderStats(items) {
    if (!items.length) {
      dom.stats.innerHTML = '';
      return;
    }
    const total = items.length;
    const urgent = items.filter((item) => {
      const created = item.record && item.record.createdAt ? new Date(item.record.createdAt) : null;
      if (!created) return false;
      return (Date.now() - created.getTime()) > 24 * 60 * 60 * 1000;
    }).length;
    dom.stats.innerHTML = `
      <article class="kpi"><div class="kpi-label">待我处理</div><div class="kpi-value">${total}</div><div class="kpi-bar"></div></article>
      <article class="kpi running"><div class="kpi-label">已等待超过 24 小时</div><div class="kpi-value">${urgent}</div><div class="kpi-bar"></div></article>`;
  }

  /** 渲染表单内容，多行文本保留换行。 */
  function renderFormValues(formValues) {
    if (!formValues || !formValues.length) {
      return '<p class="todo-empty-line">该审批单没有可展示的表单内容。</p>';
    }
    return `<dl class="form-values">${formValues.map((field) => `
      <div class="form-value">
        <dt>${escapeHtml(field.name)}</dt>
        <dd>${escapeHtml(field.value) || '<span class="muted">（空）</span>'}</dd>
      </div>`).join('')}</dl>`;
  }

  /** 渲染本系统已记录的审批意见时间线。 */
  function renderTimeline(timeline) {
    if (!timeline || !timeline.length) return '';
    return `<div class="timeline"><h4>本系统审批意见记录</h4>${timeline.map((entry) => `
      <div class="timeline-item">
        <span class="timeline-dot"></span>
        <div>
          <strong>${escapeHtml(entry.userNick || entry.userId)} · ${escapeHtml(ACTION_LABELS[entry.action] || entry.action)}</strong>
          <small>${formatDate(entry.createdAt)}${entry.sourceType === 'SYSTEM' ? ' · 系统自动生成' : ''}</small>
          <p>${escapeHtml(entry.remark)}</p>
        </div>
      </div>`).join('')}</div>`;
  }

  /** 渲染单条待办卡片。 */
  function renderItem(item) {
    const record = item.record || {};
    const title = record.title || record.businessId || '未命名审批';
    return `
    <article class="panel todo-card" data-instance="${escapeHtml(record.instanceId)}">
      <header class="todo-head">
        <div>
          <h3>${escapeHtml(title)}</h3>
          <p class="todo-meta">
            发起人 ${escapeHtml(item.originator || '-')} · 部门 ${escapeHtml(record.deptId || '-')} ·
            提交于 ${formatDate(record.createdAt)}
          </p>
          <p class="todo-meta muted">实例编号 ${escapeHtml(record.instanceId)}</p>
        </div>
        <span class="badge ${escapeHtml(record.status)}">${escapeHtml(STATUS_LABELS[record.status] || record.status)}</span>
      </header>

      ${renderFormValues(item.formValues)}
      ${renderTimeline(item.timeline)}

      <div class="decision-box">
        <label for="remark-${escapeHtml(record.instanceId)}">审批意见（可留空，系统会自动生成并标注）</label>
        <textarea id="remark-${escapeHtml(record.instanceId)}" class="textarea" rows="3"
                  placeholder="填写同意或拒绝的具体理由，将同步到钉钉审批记录"></textarea>
        <div class="decision-actions">
          <button class="btn btn-primary" data-action="agree" type="button">同意</button>
          <button class="btn btn-danger" data-action="refuse" type="button">拒绝</button>
          <button class="btn" data-action="comment" type="button">仅提交意见</button>
        </div>
      </div>
    </article>`;
  }

  function render(items) {
    renderStats(items);
    if (!items.length) {
      dom.list.innerHTML = '<div class="panel todo-empty">当前没有需要你处理的审批单。</div>';
      return;
    }
    dom.list.innerHTML = items.map(renderItem).join('');
  }

  /** 加载待办列表。 */
  async function load() {
    dom.reload.disabled = true;
    setMessage('正在查询待办…');
    try {
      const session = await api('/api/dingtalk/session');
      renderUserProfile(session);
      if (session.canViewStatistics) {
        document.querySelectorAll('.manager-only').forEach((element) => element.classList.add('allowed'));
      }
      if (!session.canHandleTasks) {
        throw new Error('你不在审批人名单中，无法处理审批单。');
      }
      csrfToken = (await api('/api/dingtalk/csrf')).csrfToken;
      const data = await api('/api/dingtalk/approvals/todo');
      render(data.items || []);
      setMessage(data.items && data.items.length ? '' : '当前没有需要你处理的审批单。', 'success');
    } catch (error) {
      dom.list.innerHTML = '';
      dom.stats.innerHTML = '';
      dom.loginPrompt.classList.remove('hidden');
      dom.loginPrompt.querySelector('strong').textContent = '无法加载待办';
      dom.loginPrompt.querySelector('span').textContent = error.message;
      setMessage(error.message, 'error');
    } finally {
      dom.reload.disabled = false;
    }
  }

  /** 提交审批决策或审批意见。 */
  async function submit(instanceId, action) {
    const textarea = document.querySelector('#remark-' + CSS.escape(instanceId));
    const remark = textarea ? textarea.value.trim() : '';
    if (action === 'comment' && !remark) {
      setMessage('请先填写审批意见再提交。', 'error');
      return;
    }
    if (action !== 'comment' && !window.confirm(
      '确认' + (action === 'agree' ? '同意' : '拒绝') + '该审批单？提交后会立即写回钉钉，无法撤销。')) {
      return;
    }
    document.querySelectorAll(`.todo-card[data-instance="${CSS.escape(instanceId)}"] button`)
      .forEach((button) => { button.disabled = true; });
    setMessage('正在提交到钉钉…');
    try {
      if (action === 'comment') {
        await api(`/api/dingtalk/approvals/${encodeURIComponent(instanceId)}/comments`,
          { method: 'POST', body: JSON.stringify({ content: remark }) });
        setMessage('审批意见已提交到钉钉。', 'success');
      } else {
        await api(`/api/dingtalk/approvals/${encodeURIComponent(instanceId)}/decision`,
          { method: 'POST', body: JSON.stringify({ action: action, remark: remark }) });
        setMessage('审批结果已提交到钉钉。', 'success');
      }
      await load();
    } catch (error) {
      setMessage(error.message, 'error');
      document.querySelectorAll(`.todo-card[data-instance="${CSS.escape(instanceId)}"] button`)
        .forEach((button) => { button.disabled = false; });
    }
  }

  dom.reload.addEventListener('click', load);
  dom.list.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const card = button.closest('.todo-card');
    if (!card) return;
    submit(card.dataset.instance, button.dataset.action);
  });

  load();
})();
