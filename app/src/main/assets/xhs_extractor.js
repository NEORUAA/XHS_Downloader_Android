/** Return only the current note's structured state, retaining ordered Live Photo pairs. */
(function () {
    const match = location.pathname.match(/\/(?:explore|discovery\/item|user\/profile\/[^/]+)\/([a-f0-9]{24})/i);
    const expected = match ? match[1] : null;
    if (!expected) return JSON.stringify({error: 'unsupported_page'});
    const roots = [window.__INITIAL_STATE__, window.__NEXT_DATA__, window.__NUXT__];
    const seen = new Set();
    const queue = roots.filter(Boolean);
    let visits = 0;
    while (queue.length && visits++ < 10000) {
        const value = queue.shift();
        if (!value || typeof value !== 'object' || seen.has(value)) continue;
        seen.add(value);
        const detail = value.noteDetailMap && value.noteDetailMap[expected];
        if (detail && detail.note && (!detail.note.noteId || detail.note.noteId === expected)) {
            return JSON.stringify({note: detail.note, comments: detail.comments, url: location.href});
        }
        const mobile = value.noteData && value.noteData.data;
        if (mobile && mobile.noteData && mobile.noteData.noteId === expected) {
            return JSON.stringify({note: mobile.noteData, comments: mobile.commentData, url: location.href});
        }
        if (value.noteId === expected && (value.imageList || value.video || value.desc !== undefined)) {
            return JSON.stringify({note: value, url: location.href});
        }
        for (const key of Object.keys(value)) {
            const child = value[key];
            if (child && typeof child === 'object') queue.push(child);
        }
    }
    return JSON.stringify({error: 'note_state_unavailable'});
})();
