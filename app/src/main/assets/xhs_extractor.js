/**
 * XHS Image Extractor
 * Purpose: Extract image and video URLs from Xiaohongshu (XHS) posts
 * Target Elements: .media-container img and .media-container video only
 * Filtering: Only URLs that contain 'http', process 'blob:' URLs by stripping the prefix
 */

(function() {
    var urls = [];
    var seenUrls = new Set();  // Use Set for automatic deduplication
    
    try {
        console.log('=== XHS Image Extractor Started ===');
        
        // Method 1: Find all img elements within .media-container
        var imgElements = document.querySelectorAll('.media-container img');
        console.log('Found ' + imgElements.length + ' .media-container img elements');
        
        for (var i = 0; i < imgElements.length; i++) {
            var element = imgElements[i];
            var src = element.src;
            
            // Log element for debugging
            console.log('Img ' + i + ': src="' + src + '", alt="' + element.alt + '", className="' + element.className + '"');
            
            // Validate and filter URL - now includes URLs containing 'http' 
            if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.includes('http') && 
                !src.startsWith('data:')) {
                
                if (!seenUrls.has(src)) {
                    console.log('✓ Added image URL: ' + src);
                    urls.push(src);
                    seenUrls.add(src);
                } else {
                    console.log('○ Duplicate image URL skipped: ' + src);
                }
            } else if (src) {
                console.log('✗ Skipped invalid image URL: ' + src);
            }
        }
        
        // Try to extract Live Photo videos from page state (these are in imageList but contain video URLs)
        var livePhotoVideos = extractLivePhotoVideosFromPageState();
        if (livePhotoVideos && livePhotoVideos.length > 0) {
            for (var i = 0; i < livePhotoVideos.length; i++) {
                var liveVideoUrl = livePhotoVideos[i];
                if (!seenUrls.has(liveVideoUrl)) {
                    console.log('✓ Added Live Photo video URL: ' + liveVideoUrl);
                    urls.push(liveVideoUrl);
                    seenUrls.add(liveVideoUrl);
                } else {
                    console.log('○ Duplicate Live Photo video URL skipped: ' + liveVideoUrl);
                }
            }
        }
        
        // Method 2: Find all video elements within .media-container
        var videoElements = document.querySelectorAll('.media-container video');
        console.log('Found ' + videoElements.length + ' .media-container video elements');
        
        // For videos, also try to get the actual URL from page state if blob URL is found
        for (var i = 0; i < videoElements.length; i++) {
            var element = videoElements[i];
            var src = element.src;
            
            // Log element for debugging
            console.log('Video ' + i + ': src="' + src + '", className="' + element.className + '" mediaType="' + element.getAttribute('mediatype') + '"');
            
            // Handle blob URLs by trying to extract the real URL from page state
            if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.startsWith('blob:')) {
                
                // Try to extract the actual video URL from the page's state
                var actualVideoUrl = extractVideoUrlFromPageState();
                if (actualVideoUrl) {
                    if (!seenUrls.has(actualVideoUrl)) {
                        console.log('✓ Added actual video URL from page state: ' + actualVideoUrl);
                        urls.push(actualVideoUrl);
                        seenUrls.add(actualVideoUrl);
                    } else {
                        console.log('○ Duplicate actual video URL skipped: ' + actualVideoUrl);
                    }
                } else {
                    // If we can't extract from page state, try to determine if this is a video element
                    // and possibly get the video URL in other ways
                    if (element.getAttribute('mediatype') === 'video' || element.tagName.toLowerCase() === 'video') {
                        console.log('✗ Could not extract actual video URL from page state for blob: ' + src);
                    }
                }
            }
            // Handle regular HTTP URLs
            else if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.includes('http') && 
                !src.startsWith('data:')) {
                
                if (!seenUrls.has(src)) {
                    console.log('✓ Added video URL: ' + src);
                    urls.push(src);
                    seenUrls.add(src);
                } else {
                    console.log('○ Duplicate video URL skipped: ' + src);
                }
            } else if (src) {
                console.log('✗ Skipped invalid video URL: ' + src);
            }
        }
        

        
        console.log('=== Extraction Complete ===');
        console.log('Total unique URLs found: ' + urls.length);
        console.log('URLs: ', urls);
        
    } catch (error) {
        console.error('❌ Error in XHS image extraction:', error);
        console.error('Error stack:', error.stack);
        return []; // Return empty array on error
    }
    
    // Function to extract actual video URL from page state (similar to Python project)
    function extractVideoUrlFromPageState() {
        try {
            // Check if the page has the initial state object (as used in Python project)
            if (window.__INITIAL_STATE__ && window.__INITIAL_STATE__.note) {
                // Try to get the actual video URL from the note data
                var noteData = window.__INITIAL_STATE__.note;
                
                // Look for video key in the note data structure (matching Python project structure)
                if (noteData.noteDetailMap) {
                    var noteId = null;
                    // Extract note ID from URL if needed to get the specific note data
                    var pathParts = window.location.pathname.split('/');
                    if (pathParts.length >= 3 && pathParts[1] === 'explore') {
                        noteId = pathParts[2].split('?')[0];
                        
                        // First check for regular video (video type posts)
                        if (noteData.noteDetailMap[noteId] && 
                            noteData.noteDetailMap[noteId].note && 
                            noteData.noteDetailMap[noteId].note.video &&
                            noteData.noteDetailMap[noteId].note.video.consumer &&
                            noteData.noteDetailMap[noteId].note.video.consumer.originVideoKey) {
                            
                            var originVideoKey = noteData.noteDetailMap[noteId].note.video.consumer.originVideoKey;
                            var actualVideoUrl = 'https://sns-video-bd.xhscdn.com/' + originVideoKey;
                            
                            console.log('✓ Extracted actual video URL from page state: ' + actualVideoUrl);
                            return actualVideoUrl;
                        }
                        
                        // Check for Live Photo videos in imageList (Live Photo posts)
                        // This is based on Python project's Image.get_image_link method
                        if (noteData.noteDetailMap[noteId] && 
                            noteData.noteDetailMap[noteId].note && 
                            noteData.noteDetailMap[noteId].note.imageList) {
                            
                            var imageList = noteData.noteDetailMap[noteId].note.imageList;
                            for (var i = 0; i < imageList.length; i++) {
                                var imageItem = imageList[i];
                                // Check for Live Photo video URL in stream.h264[0].masterUrl
                                if (imageItem.stream && 
                                    imageItem.stream.h264 && 
                                    imageItem.stream.h264[0] && 
                                    imageItem.stream.h264[0].masterUrl) {
                                    
                                    var livePhotoVideoUrl = imageItem.stream.h264[0].masterUrl;
                                    console.log('✓ Extracted Live Photo video URL from page state: ' + livePhotoVideoUrl);
                                    return livePhotoVideoUrl;
                                }
                            }
                        }
                    }
                }
            }
            
            // Additional attempt: Look for embedded JSON in script tags
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var script = scripts[i];
                if (script.textContent && script.textContent.includes('__INITIAL_STATE__')) {
                    var match = script.textContent.match(/window\.__INITIAL_STATE__\s*=\s*({.*?});/);
                    if (match && match[1]) {
                        try {
                            var initialState = JSON.parse(match[1]);
                            if (initialState.note && initialState.note.noteDetailMap) {
                                // Similar logic as above
                                var pathParts = window.location.pathname.split('/');
                                if (pathParts.length >= 3 && pathParts[1] === 'explore') {
                                    var noteId = pathParts[2].split('?')[0];
                                    
                                    // Check for regular video
                                    if (initialState.note.noteDetailMap[noteId] && 
                                        initialState.note.noteDetailMap[noteId].note && 
                                        initialState.note.noteDetailMap[noteId].note.video &&
                                        initialState.note.noteDetailMap[noteId].note.video.consumer &&
                                        initialState.note.noteDetailMap[noteId].note.video.consumer.originVideoKey) {
                                        
                                        var originVideoKey = initialState.note.noteDetailMap[noteId].note.video.consumer.originVideoKey;
                                        var actualVideoUrl = 'https://sns-video-bd.xhscdn.com/' + originVideoKey;
                                        
                                        console.log('✓ Extracted actual video URL from script tag: ' + actualVideoUrl);
                                        return actualVideoUrl;
                                    }
                                    
                                    // Check for Live Photo videos in imageList
                                    if (initialState.note.noteDetailMap[noteId] && 
                                        initialState.note.noteDetailMap[noteId].note && 
                                        initialState.note.noteDetailMap[noteId].note.imageList) {
                                        
                                        var imageList = initialState.note.noteDetailMap[noteId].note.imageList;
                                        for (var j = 0; j < imageList.length; j++) {
                                            var imageItem = imageList[j];
                                            // Check for Live Photo video URL in stream.h264[0].masterUrl
                                            if (imageItem.stream && 
                                                imageItem.stream.h264 && 
                                                imageItem.stream.h264[0] && 
                                                imageItem.stream.h264[0].masterUrl) {
                                                
                                                var livePhotoVideoUrl = imageItem.stream.h264[0].masterUrl;
                                                console.log('✓ Extracted Live Photo video URL from script tag: ' + livePhotoVideoUrl);
                                                return livePhotoVideoUrl;
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e) {
                            console.log('✗ Error parsing __INITIAL_STATE__ from script: ' + e.message);
                        }
                    }
                }
            }
        } catch (e) {
            console.log('✗ Error extracting video from page state: ' + e.message);
        }
        
        return null;
    }
    
    // Function to extract Live Photo videos from page state (based on Python project's __get_live_link method)
    function extractLivePhotoVideosFromPageState() {
        var livePhotoVideos = [];
        
        try {
            // Check if the page has the initial state object
            if (window.__INITIAL_STATE__ && window.__INITIAL_STATE__.note) {
                var noteData = window.__INITIAL_STATE__.note;
                
                if (noteData.noteDetailMap) {
                    var noteId = null;
                    var pathParts = window.location.pathname.split('/');
                    if (pathParts.length >= 3 && pathParts[1] === 'explore') {
                        noteId = pathParts[2].split('?')[0];
                        
                        if (noteData.noteDetailMap[noteId] && 
                            noteData.noteDetailMap[noteId].note && 
                            noteData.noteDetailMap[noteId].note.imageList) {
                            
                            var imageList = noteData.noteDetailMap[noteId].note.imageList;
                            for (var i = 0; i < imageList.length; i++) {
                                var imageItem = imageList[i];
                                // Check for Live Photo video URL in stream.h264[0].masterUrl
                                if (imageItem.stream && 
                                    imageItem.stream.h264 && 
                                    imageItem.stream.h264[0] && 
                                    imageItem.stream.h264[0].masterUrl) {
                                    
                                    var livePhotoVideoUrl = imageItem.stream.h264[0].masterUrl;
                                    livePhotoVideos.push(livePhotoVideoUrl);
                                    console.log('✓ Found Live Photo video URL from page state: ' + livePhotoVideoUrl);
                                }
                            }
                        }
                    }
                }
            }
            
            // Additional attempt: Look for embedded JSON in script tags
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var script = scripts[i];
                if (script.textContent && script.textContent.includes('__INITIAL_STATE__')) {
                    var match = script.textContent.match(/window\.__INITIAL_STATE__\s*=\s*({.*?});/);
                    if (match && match[1]) {
                        try {
                            var initialState = JSON.parse(match[1]);
                            if (initialState.note && initialState.note.noteDetailMap) {
                                var pathParts = window.location.pathname.split('/');
                                if (pathParts.length >= 3 && pathParts[1] === 'explore') {
                                    var noteId = pathParts[2].split('?')[0];
                                    
                                    if (initialState.note.noteDetailMap[noteId] && 
                                        initialState.note.noteDetailMap[noteId].note && 
                                        initialState.note.noteDetailMap[noteId].note.imageList) {
                                        
                                        var imageList = initialState.note.noteDetailMap[noteId].note.imageList;
                                        for (var j = 0; j < imageList.length; j++) {
                                            var imageItem = imageList[j];
                                            // Check for Live Photo video URL in stream.h264[0].masterUrl
                                            if (imageItem.stream && 
                                                imageItem.stream.h264 && 
                                                imageItem.stream.h264[0] && 
                                                imageItem.stream.h264[0].masterUrl) {
                                                
                                                var livePhotoVideoUrl = imageItem.stream.h264[0].masterUrl;
                                                // Only add if not already in results
                                                if (!livePhotoVideos.includes(livePhotoVideoUrl)) {
                                                    livePhotoVideos.push(livePhotoVideoUrl);
                                                    console.log('✓ Found Live Photo video URL from script tag: ' + livePhotoVideoUrl);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e) {
                            console.log('✗ Error parsing __INITIAL_STATE__ from script for Live Photos: ' + e.message);
                        }
                    }
                }
            }
        } catch (e) {
            console.log('✗ Error extracting Live Photo videos from page state: ' + e.message);
        }
        
        return livePhotoVideos;
    }
    
    // Function to extract content (title, desc, tagList) from page state
    function getContent() {
        try {
            // Try to get from window.__INITIAL_STATE__ first
            if (window.__INITIAL_STATE__) {
                var contentJson = window.__INITIAL_STATE__;
                var pathParts = window.location.pathname.split('/');
                var noteId = null;

                if (pathParts.length >= 3 && pathParts[1] === 'explore') {
                    noteId = pathParts[2].split('?')[0];
                }

                // First try to get from note.noteDetailMap[postId].note
                var note = contentJson?.['note']?.['noteDetailMap']?.[noteId]?.['note'];

                // If not found, try alternative structures
                if (!note && contentJson?.['note']?.['note']) {
                    // Direct access to note object
                    note = contentJson['note']['note'];
                }

                // If still not found, look for feed items
                if (!note && contentJson?.['note']?.['feed']?.['items']?.[0]) {
                    note = contentJson['note']['feed']['items'][0];
                }

                if (note) {
                    var title = note['title'] || '';
                    var desc = note['desc'] || '';
                    var tagList = (note['tagList'] || []).map(function(item) {
                        return item.name;
                    });

                    console.log('提取的内容: ', title, desc, tagList);

                    // Format content as title + \n + desc + \n + tagList (joined with spaces or commas)
                    var tagsStr = tagList.join(' ');
                    var content = title + (title ? '\n' : '') + desc + (desc && tagsStr ? '\n' : '') + tagsStr;

                    return {
                        title: title,
                        desc: desc,
                        tagList: tagList,
                        content: content.trim()  // Remove leading/trailing whitespace
                    };
                }
            }

            // Alternative: Extract from embedded script tags
            var scripts = document.querySelectorAll('script');
            for (var i = 0; i < scripts.length; i++) {
                var script = scripts[i];
                if (script.textContent && script.textContent.includes('__INITIAL_STATE__')) {
                    var contentRegex = /<script>window\.__INITIAL_STATE__=(.*?)<\/script>/;
                    var match = script.textContent.match(contentRegex);
                    if (match && match[1]) {
                        // Replace undefined with "NO VALUE" as in the reference code
                        var initialStateText = match[1].replace(/undefined/g, '"NO VALUE"');
                        try {
                            var contentJson = JSON.parse(initialStateText);
                            var pathParts = window.location.pathname.split('/');
                            var noteId = null;

                            if (pathParts.length >= 3 && pathParts[1] === 'explore') {
                                noteId = pathParts[2].split('?')[0];
                            }

                            var note = contentJson?.['note']?.['noteDetailMap']?.[noteId]?.['note'];

                            if (note) {
                                var title = note['title'] || '';
                                var desc = note['desc'] || '';
                                var tagList = (note['tagList'] || []).map(function(item) {
                                    return item.name;
                                });

                                console.log('从script标签提取的内容: ', title, desc, tagList);

                                var tagsStr = tagList.join(' ');
                                var content = title + (title ? '\n' : '') + desc + (desc && tagsStr ? '\n' : '') + tagsStr;

                                return {
                                    title: title,
                                    desc: desc,
                                    tagList: tagList,
                                    content: content.trim()
                                };
                            }
                        } catch (e) {
                            console.log('从script标签解析内容时出错: ' + e.message);
                        }
                    }
                }
            }
        } catch (error) {
            console.error('提取内容时出错: ', error);
        }

        // Return empty content if extraction failed
        return {
            title: '',
            desc: '',
            tagList: [],
            content: ''
        };
    }

    // Extract content
    var content = getContent();

    // Return both URLs and content
    return {
        urls: urls,
        content: content
    };
})()