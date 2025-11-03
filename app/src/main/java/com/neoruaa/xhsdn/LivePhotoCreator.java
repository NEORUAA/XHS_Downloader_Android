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
            
            // Read the image and video files
            byte[] imageBytes = readFileToBytes(imageFile);
            byte[] videoBytes = readFileToBytes(videoFile);
            
            Log.d(TAG, "Read " + imageBytes.length + " bytes from image and " + videoBytes.length + " bytes from video");
            
            // Calculate the video start position after XMP insertion
            // First create the XMP to determine its size
            int videoOffset = imageBytes.length; // initial estimate
            String xmpData = generateXMPMetadata(videoBytes.length, videoOffset);
            byte[] xmpSegment = createXmpApp1Segment(xmpData);
            
            // Now we know the actual video offset after XMP insertion
            int actualVideoOffset = imageBytes.length + xmpSegment.length;
            
            // Recreate XMP with the correct offset
            String correctedXmpData = generateXMPMetadata(videoBytes.length, actualVideoOffset);
            byte[] correctedXmpSegment = createXmpApp1Segment(correctedXmpData);
            
            // Insert XMP right after SOI (0xFFD8) at position 2
            byte[] imageWithXmp = new byte[imageBytes.length + correctedXmpSegment.length];
            System.arraycopy(imageBytes, 0, imageWithXmp, 0, 2); // Copy SOI marker
            System.arraycopy(correctedXmpSegment, 0, imageWithXmp, 2, correctedXmpSegment.length); // Insert XMP
            System.arraycopy(imageBytes, 2, imageWithXmp, 2 + correctedXmpSegment.length, imageBytes.length - 2); // Copy rest of image
            
            // Combine image with XMP + video at the end
            byte[] result = new byte[imageWithXmp.length + videoBytes.length];
            System.arraycopy(imageWithXmp, 0, result, 0, imageWithXmp.length);
            System.arraycopy(videoBytes, 0, result, imageWithXmp.length, videoBytes.length);
            
            // Write the live photo bytes to output file
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(result);
            }
            
            Log.d(TAG, "Successfully created live photo with total size: " + outputFile.length() + " bytes");
            
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
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">\n" +
            "   <GCamera:MotionPhoto>1</GCamera:MotionPhoto>\n" +
            "   <GCamera:MotionPhotoVersion>1</GCamera:MotionPhotoVersion>\n" +
            "   <GCamera:MotionPhotoPresentationTimestampUs>0</GCamera:MotionPhotoPresentationTimestampUs>\n" +
            "   <GCamera:MicroVideo>1</GCamera:MicroVideo>\n" +
            "   <GCamera:MicroVideoVersion>1</GCamera:MicroVideoVersion>\n" +
            "   <GCamera:MicroVideoPresentationTimestampUs>0</GCamera:MicroVideoPresentationTimestampUs>\n" +
            "   <GCamera:MicroVideoOffset>%d</GCamera:MicroVideoOffset>\n" +
            "   <GCamera:MicroVideoDuration>1000000</GCamera:MicroVideoDuration>\n" +
            "   <Container:Directory>\n" +
            "      <rdf:Seq>\n" +
            "         <rdf:li rdf:parseType=\"Resource\">\n" +
            "            <Item:Mime>image/jpeg</Item:Mime>\n" +
            "            <Item:Semantic>Primary</Item:Semantic>\n" +
            "            <Item:Length>0</Item:Length>\n" +
            "         </rdf:li>\n" +
            "         <rdf:li rdf:parseType=\"Resource\">\n" +
            "            <Item:Mime>video/mp4</Item:Mime>\n" +
            "            <Item:Semantic>MotionPhoto</Item:Semantic>\n" +
            "            <Item:Length>%d</Item:Length>\n" +
            "         </rdf:li>\n" +
            "      </rdf:Seq>\n" +
            "   </Container:Directory>\n" +
            "</rdf:Description>\n" +
            "</rdf:RDF>\n" +
            "</x:xmpmeta>",
            videoOffset, videoSize
        );
    }
    
    /**
     * Creates an APP1 XMP segment with proper JPEG header
     * @param xmpData The XMP data as string
     * @return Byte array representing the APP1 XMP segment
     */
    private static byte[] createXmpApp1Segment(String xmpData) throws IOException {
        byte[] xmpPayload = xmpData.getBytes("UTF-8");
        byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/\0".getBytes("UTF-8");
        
        // The APP1 segment format is: [0xFFE1] [Length: 2 bytes] [Header] [Payload]
        // The Length field should contain the size of everything after the length field
        // So Length = size of (header + payload)
        int lengthField = xmpHeader.length + xmpPayload.length;
        
        // Create the segment
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        
        // Write APP1 marker (0xFFE1)
        segment.write(0xFF);
        segment.write(0xE1);
        
        // Write length field (2 bytes, big-endian) - value is size of header + payload
        segment.write((lengthField >> 8) & 0xFF);
        segment.write(lengthField & 0xFF);
        
        // Write XMP header
        segment.write(xmpHeader);
        
        // Write XMP data
        segment.write(xmpPayload);
        
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
}