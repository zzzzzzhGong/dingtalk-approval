function renderUserProfile(user) {
  const root = document.querySelector('#userProfile');
  if (!root || !user) return;
  root.replaceChildren();
  const avatarUrl = /^https:\/\//i.test(user.avatarUrl || '') ? user.avatarUrl : '';
  let avatar;
  if (avatarUrl) {
    avatar = document.createElement('img');
    avatar.src = avatarUrl;
    avatar.alt = '';
    avatar.referrerPolicy = 'no-referrer';
  } else {
    avatar = document.createElement('span');
    avatar.textContent = (user.nick || '钉').trim().slice(0, 1).toUpperCase();
  }
  avatar.className = 'profile-avatar';
  const copy = document.createElement('span');
  copy.className = 'profile-copy';
  const name = document.createElement('strong');
  name.textContent = user.nick || '钉钉用户';
  const role = document.createElement('span');
  role.textContent = user.canViewStatistics ? '审批管理' : '钉钉用户';
  copy.append(name, role);
  root.append(avatar, copy);
  root.classList.add('visible');
}
