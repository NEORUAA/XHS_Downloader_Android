/**
 * XHS Image Extractor
 * Purpose: Extract image and video URLs from Xiaohongshu (XHS) posts
 * Target Elements: .img-container img and .img-container video
 * Filtering: Only URLs that start with 'http', exclude 'blob:' and 'data:' URLs
 */

(function() {
    var urls = [];
    var seenUrls = new Set();  // Use Set for automatic deduplication
    
    try {
        console.log('=== XHS Image Extractor Started ===');
        
        // Method 1: Find all img elements within .img-container
        var imgElements = document.querySelectorAll('.img-container img');
        console.log('Found ' + imgElements.length + ' .img-container img elements');
        
        for (var i = 0; i < imgElements.length; i++) {
            var element = imgElements[i];
            var src = element.src;
            
            // Log element for debugging
            console.log('Img ' + i + ': src="' + src + '", alt="' + element.alt + '", className="' + element.className + '"');
            
            // Validate and filter URL
            if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.startsWith('http') && 
                !src.startsWith('blob:') && 
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
        
        // Method 2: Find all video elements within .img-container
        var videoElements = document.querySelectorAll('.img-container video');
        console.log('Found ' + videoElements.length + ' .img-container video elements');
        
        for (var i = 0; i < videoElements.length; i++) {
            var element = videoElements[i];
            var src = element.src;
            
            // Log element for debugging
            console.log('Video ' + i + ': src="' + src + '", className="' + element.className + '"');
            
            // Validate and filter URL
            if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.startsWith('http') && 
                !src.startsWith('blob:') && 
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
        
        // Also look for images and videos in swiper slides that might not be in .img-container
        var swiperImgs = document.querySelectorAll('.swiper-slide img');
        console.log('Found ' + swiperImgs.length + ' .swiper-slide img elements');
        
        for (var i = 0; i < swiperImgs.length; i++) {
            var element = swiperImgs[i];
            var src = element.src;
            
            // Log element for debugging
            console.log('Swiper Img ' + i + ': src="' + src + '", alt="' + element.alt + '", className="' + element.className + '"');
            
            // Validate and filter URL
            if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.startsWith('http') && 
                !src.startsWith('blob:') && 
                !src.startsWith('data:')) {
                
                if (!seenUrls.has(src)) {
                    console.log('✓ Added swiper image URL: ' + src);
                    urls.push(src);
                    seenUrls.add(src);
                } else {
                    console.log('○ Duplicate swiper image URL skipped: ' + src);
                }
            } else if (src) {
                console.log('✗ Skipped invalid swiper image URL: ' + src);
            }
        }
        
        var swiperVideos = document.querySelectorAll('.swiper-slide video');
        console.log('Found ' + swiperVideos.length + ' .swiper-slide video elements');
        
        for (var i = 0; i < swiperVideos.length; i++) {
            var element = swiperVideos[i];
            var src = element.src;
            
            // Log element for debugging
            console.log('Swiper Video ' + i + ': src="' + src + '", className="' + element.className + '"');
            
            // Validate and filter URL
            if (src && 
                typeof src === 'string' && 
                src.trim() !== '' && 
                src.startsWith('http') && 
                !src.startsWith('blob:') && 
                !src.startsWith('data:')) {
                
                if (!seenUrls.has(src)) {
                    console.log('✓ Added swiper video URL: ' + src);
                    urls.push(src);
                    seenUrls.add(src);
                } else {
                    console.log('○ Duplicate swiper video URL skipped: ' + src);
                }
            } else if (src) {
                console.log('✗ Skipped invalid swiper video URL: ' + src);
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
    
    // Return the array of URLs
    return urls;
})()