/** Read the signed-in user only, never the currently viewed author or note. */
(function () {
    const unwrap = value => value && value.__v_isRef ? value.value : value;
    const user = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.user;
    if (!user) return JSON.stringify({});
    const loggedIn = unwrap(user.loggedIn);
    if (typeof loggedIn !== 'boolean') return JSON.stringify({});
    if (!loggedIn) return JSON.stringify({loggedIn: false});
    const info = unwrap(user.userInfo);
    if (!info || info.guest === true || !info.userId) return JSON.stringify({});
    return JSON.stringify({loggedIn: true, userId: info.userId, nickname: info.nickname || '', avatar: info.images || info.imageb || ''});
})();
