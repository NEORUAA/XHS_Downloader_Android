package com.neoruaa.xhsdn;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class LivePhotoCreator {
    private static final String TAG = "LivePhotoCreator";

    /**
     * Creates a live photo by embedding video into image with XMP metadata
     * Based on the Motion Photo format specification
     * @param imageFile The image file to use as the primary content
     * @param videoFile The video file to embed
     * @param outputFile The output live photo file
     * @return True if successful, false otherwise
     */
    public static boolean createLivePhoto(File imageFile, File videoFile, File outputFile) {
        try {
            Log.d(TAG, "Creating live photo from image: " + imageFile.getAbsolutePath() + 
                   " (size: " + imageFile.length() + " bytes) and video: " + videoFile.getAbsolutePath() + 
                   " (size: " + videoFile.length() + " bytes) -> output: " + outputFile.getAbsolutePath());
            
            // Read the image and video file sizes without loading them completely
            long videoSize = videoFile.length();
            
            // Use a reasonable estimate for image size in XMP (will be corrected later)
            long initialEstimate = imageFile.length(); 
            String xmpDataStr = generateXMPMetadata((int)videoSize, (int)initialEstimate);
            byte[] xmpData = xmpDataStr.getBytes("UTF-8");
            byte[] xmpSegment = createXmpApp1Segment(xmpData);
            
            // Now calculate the actual video offset after XMP insertion
            long actualVideoOffset = imageFile.length() + xmpSegment.length;
            
            // Recreate XMP with the correct offset
            String correctedXmpDataStr = generateXMPMetadata((int)videoSize, (int)actualVideoOffset);
            byte[] correctedXmpData = correctedXmpDataStr.getBytes("UTF-8");
            xmpSegment = createXmpApp1Segment(correctedXmpData);
            
            // Create the live photo using streaming approach to avoid memory issues
            return createLivePhotoStreaming(imageFile, videoFile, outputFile, xmpSegment);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating live photo: " + e.getMessage());
            e.printStackTrace();
            // If the file was created but is invalid, delete it
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        }
    }
    

    
    /**
     * Generates XMP metadata for live photo following Google's Motion Photo specification
     * @param videoSize The size of the embedded video in bytes
     * @param videoOffset The offset where the video starts in the file
     * @return XMP metadata string
     */
    private static String generateXMPMetadata(int videoSize, int videoOffset) {
        return String.format(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"" +
            "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
            "    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"" +
            "    xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\"" +
            "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
            "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
            "  GCamera:MotionPhoto=\"1\"" +
            "  GCamera:MotionPhotoVersion=\"1\"" +
            "  GCamera:MotionPhotoPresentationTimestampUs=\"0\"" +
            "  OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"" +
            "  OpCamera:MotionPhotoOwner=\"xhs\"" +
            "  OpCamera:OLivePhotoVersion=\"2\"" +
            "  OpCamera:VideoLength=\"%d\"" +
            "  GCamera:MicroVideoVersion=\"1\"" +
            "  GCamera:MicroVideo=\"1\"" +
            "  GCamera:MicroVideoOffset=\"%d\"" +
            "  GCamera:MicroVideoPresentationTimestampUs=\"0\"" +
            "  MiCamera:XMPMeta=\"&lt;?xml version='1.0' encoding='UTF-8' standalone='yes' ?&gt;\">" +
            "  <Container:Directory>" +
            "    <rdf:Seq>" +
            "      <rdf:li rdf:parseType=\"Resource\">" +
            "        <Container:Item" +
            "          Item:Mime=\"image/jpeg\"" +
            "          Item:Semantic=\"Primary\"" +
            "          Item:Length=\"0\"" +
            "          Item:Padding=\"0\"/>" +
            "      </rdf:li>" +
            "      <rdf:li rdf:parseType=\"Resource\">" +
            "        <Container:Item" +
            "          Item:Mime=\"video/mp4\"" +
            "          Item:Semantic=\"MotionPhoto\"" +
            "          Item:Length=\"%d\"/>" +
            "      </rdf:li>" +
            "    </rdf:Seq>" +
            "  </Container:Directory>" +
            "</rdf:Description>" +
            "</rdf:RDF>" +
            "</x:xmpmeta>",
            videoSize, videoOffset, videoSize
        );
    }
    
    /**
     * Creates an APP1 XMP segment with proper JPEG header
     * @param xmpData The XMP data as bytes
     * @return Byte array representing the APP1 XMP segment
     */
    private static byte[] createXmpApp1Segment(byte[] xmpData) throws IOException {
        // XMP header: "http://ns.adobe.com/xap/1.0/\0"
        byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/\0".getBytes("UTF-8");
        
        // Calculate total segment length: xmp header + xmp data + 2 bytes for length field
        int segmentLengthWithoutLengthField = xmpHeader.length + xmpData.length;
        int totalSegmentLength = segmentLengthWithoutLengthField + 2; // +2 for the length field itself
        
        // Create the segment
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        
        // Write APP1 marker (0xFFE1)
        segment.write(0xFF);
        segment.write(0xE1);
        
        // Write length field (2 bytes, big-endian)
        segment.write((totalSegmentLength >> 8) & 0xFF);
        segment.write(totalSegmentLength & 0xFF);
        
        // Write XMP header
        segment.write(xmpHeader);
        
        // Write XMP data
        segment.write(xmpData);
        
        return segment.toByteArray();
    }
    
    /**
     * Validates if the created live photo is valid
     * @param file The file to validate
     * @return true if valid, false otherwise
     */
    private static boolean isLivePhotoValid(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[10];
            int bytesRead = fis.read(header);
            if (bytesRead < 2) {
                return false;
            }
            
            // Check for JPEG SOI marker (0xFFD8)
            if (header[0] != (byte) 0xFF || header[1] != (byte) 0xD8) {
                Log.d(TAG, "File does not have valid JPEG SOI marker");
                return false;
            }
            
            // Check for the presence of XMP metadata by reading first part of file
            byte[] buffer = new byte[16384]; // Read first 16KB to look for XMP
            fis.close(); // Close the first stream
            
            // Read data to check for XMP metadata
            try (FileInputStream fis2 = new FileInputStream(file)) {
                int totalRead = 0;
                while (totalRead < buffer.length && (bytesRead = fis2.read(buffer, totalRead, buffer.length - totalRead)) != -1) {
                    totalRead += bytesRead;
                }
                
                String content = new String(buffer, 0, totalRead, "UTF-8");
                
                // Look for XMP signatures
                boolean hasXmpMeta = content.contains("xmpmeta");
                boolean hasMotionPhoto = content.contains("MotionPhoto");
                boolean hasMicroVideo = content.contains("MicroVideo");
                
                Log.d(TAG, "XMP validation - xmpmeta: " + hasXmpMeta + ", MotionPhoto: " + hasMotionPhoto + ", MicroVideo: " + hasMicroVideo);
                
                if (!hasXmpMeta || !hasMotionPhoto) {
                    Log.d(TAG, "File does not contain valid XMP Motion Photo metadata");
                    return false;
                }
            }
            
            // Try to decode the image to make sure it's valid
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.d(TAG, "Image has invalid dimensions: " + options.outWidth + "x" + options.outHeight);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating live photo: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Reads a file into a byte array
     * @param file The file to read
     * @return Byte array containing the file contents
     * @throws IOException
     */
    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            
            int nRead;
            byte[] data = new byte[16384]; // 16KB buffer
            
            while ((nRead = fis.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            
            return buffer.toByteArray();
        }
    }
    
    /**
     * Creates a live photo using streaming approach to avoid memory issues
     * @param imageFile The image file to use as the primary content
     * @param videoFile The video file to embed
     * @param outputFile The output live photo file
     * @param xmpSegment The XMP metadata segment to insert
     * @return True if successful, false otherwise
     */
    private static boolean createLivePhotoStreaming(File imageFile, File videoFile, File outputFile, byte[] xmpSegment) {
        try (FileInputStream imageStream = new FileInputStream(imageFile);
             FileInputStream videoStream = new FileInputStream(videoFile);
             FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            
            // Write the JPEG header (first 2 bytes: SOI marker - 0xFFD8)
            byte[] headerBuffer = new byte[2];
            int bytesRead = imageStream.read(headerBuffer);
            if (bytesRead != 2) {
                Log.e(TAG, "Could not read image header");
                return false;
            }
            outputStream.write(headerBuffer);
            
            // Write the XMP segment right after SOI
            outputStream.write(xmpSegment);
            
            // Skip the first 2 bytes of the image (already written) and copy the rest
            // Copy remaining image data
            byte[] buffer = new byte[8192]; // 8KB buffer to reduce memory usage
            int totalImageBytes = (int)(imageFile.length() - 2); // Subtract the 2 bytes already read
            int copiedBytes = 0;
            
            while (copiedBytes < totalImageBytes) {
                int bytesToRead = Math.min(buffer.length, totalImageBytes - copiedBytes);
                bytesRead = imageStream.read(buffer, 0, bytesToRead);
                if (bytesRead == -1) break;
                
                outputStream.write(buffer, 0, bytesRead);
                copiedBytes += bytesRead;
            }
            
            // Copy the entire video file to the end
            long videoBytesCopied = 0;
            while (true) {
                bytesRead = videoStream.read(buffer);
                if (bytesRead == -1) break;
                
                outputStream.write(buffer, 0, bytesRead);
                videoBytesCopied += bytesRead;
            }
            
            outputStream.flush();
            
            Log.d(TAG, "Successfully created live photo with streaming approach. Image bytes copied: " + 
                  copiedBytes + ", Video bytes copied: " + videoBytesCopied + ", Total file size: " + 
                  outputFile.length());
            
            // Verify that the created file is valid
            if (!isLivePhotoValid(outputFile)) {
                Log.e(TAG, "Created live photo is not valid - failed validation check");
                if (outputFile.exists()) {
                    outputFile.delete(); // Clean up invalid file
                }
                return false;
            }
            
            Log.d(TAG, "Live photo validation passed successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in streaming live photo creation: " + e.getMessage());
            e.printStackTrace();
            // If the file was created but is invalid, delete it
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        }
    }
}