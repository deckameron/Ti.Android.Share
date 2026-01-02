package ti.android.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.FileProvider;
import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollModule;
import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.TiApplication;
import org.appcelerator.titanium.TiBlob;
import org.appcelerator.titanium.TiC;
import org.appcelerator.titanium.util.TiConvert;
import org.appcelerator.titanium.util.TiActivityResultHandler;
import org.appcelerator.titanium.util.TiActivitySupport;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShareProxy implements TiActivityResultHandler {

    private static final String TAG = "ShareProxy";
    private static final int REQUEST_CODE_SHARE = 1001;
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB default limit

    private KrollFunction callback;
    private KrollModule module;
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void share(HashMap<String, Object> params, KrollModule module) {
        ShareProxy proxy = new ShareProxy();
        proxy.module = module;
        proxy.executeShare(params);
    }

    private void executeShare(HashMap<String, Object> params) {
        try {
            String message = TiConvert.toString(params.get("message"), "");
            String subject = TiConvert.toString(params.get("subject"), "Share");
            Object callbackObj = params.get("callback");

            // Support both "media" (new) and "image" (legacy) parameters
            Object mediaObj = params.get("media");
            if (mediaObj == null) {
                mediaObj = params.get("image"); // Backward compatibility
            }

            // Store callback if provided
            if (callbackObj != null && callbackObj instanceof KrollFunction) {
                this.callback = (KrollFunction) callbackObj;
            }

            Log.d(TAG, "=== SHARE START ===");
            Log.d(TAG, "Message: " + message);
            Log.d(TAG, "Media object type: " + (mediaObj != null ? mediaObj.getClass().getName() : "null"));
            Log.d(TAG, "Callback provided: " + (this.callback != null));

            // Check if media is a URL
            if (mediaObj instanceof String && isUrl((String) mediaObj)) {
                String mediaUrl = (String) mediaObj;
                Log.d(TAG, "Detected URL media: " + mediaUrl);

                // Download media asynchronously
                downloadMediaAsync(mediaUrl, message, subject);

            } else {
                // Process normally (local file or blob)
                processShare(message, subject, mediaObj);
            }

        } catch (Exception e) {
            Log.e(TAG, "FATAL ERROR while sharing: " + e.getMessage(), e);

            // Call callback with error if available
            if (this.callback != null) {
                fireCallback(false, "Error: " + e.getMessage());
            }
        }
    }

    private boolean isUrl(String str) {
        return str != null && (str.startsWith("http://") || str.startsWith("https://"));
    }

    private String detectMediaType(String path) {
        if (path == null) {
            return "image/jpeg";
        }

        String lower = path.toLowerCase();

        // Video formats
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") ||
                lower.endsWith(".avi") || lower.endsWith(".3gp") ||
                lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".flv") || lower.endsWith(".m4v")) {
            Log.d(TAG, "Detected video format: " + path);
            return "video/*";
        }

        // Image formats (default)
        Log.d(TAG, "Detected image format: " + path);
        return "image/*";
    }

    private String getFileExtension(String path) {
        if (path == null || !path.contains(".")) {
            return ".jpg";
        }
        return path.substring(path.lastIndexOf("."));
    }

    private void downloadMediaAsync(final String mediaUrl, final String message, final String subject) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                final File mediaFile = downloadMedia(mediaUrl);

                // Return to main thread
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mediaFile != null && mediaFile.exists()) {
                            Log.d(TAG, "Download completed, proceeding with share");
                            processShare(message, subject, mediaFile);
                        } else {
                            Log.e(TAG, "Download failed, sharing text only");

                            // If callback exists, notify about download failure
                            if (callback != null) {
                                fireCallback(false, "Failed to download media");
                            } else {
                                // Share text only if no callback
                                processShare(message, subject, null);
                            }
                        }
                    }
                });
            }
        });
    }

    private File downloadMedia(String mediaUrl) {
        HttpURLConnection connection = null;

        try {
            Log.d(TAG, "Starting media download from: " + mediaUrl);

            URL url = new URL(mediaUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000); // Increased for videos
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            connection.connect();

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Get file extension from URL
                String extension = getFileExtension(mediaUrl);

                InputStream input = connection.getInputStream();

                // Create temporary file
                File cacheDir = TiApplication.getInstance().getCacheDir();
                File mediaFile = new File(cacheDir, "share_url_" + System.currentTimeMillis() + extension);

                FileOutputStream output = new FileOutputStream(mediaFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytes = 0;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // Check file size limit
                    if (totalBytes > MAX_FILE_SIZE) {
                        output.close();
                        input.close();
                        mediaFile.delete();
                        Log.e(TAG, "File exceeds maximum size limit of " + (MAX_FILE_SIZE / (1024 * 1024)) + "MB");
                        return null;
                    }
                }

                output.close();
                input.close();

                Log.d(TAG, "✓ Media downloaded successfully. Size: " + (totalBytes / 1024) + "KB");
                Log.d(TAG, "Saved to: " + mediaFile.getAbsolutePath());

                return mediaFile;

            } else {
                Log.e(TAG, "HTTP error code: " + responseCode);
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Download failed: " + e.getMessage(), e);
            return null;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void processShare(String message, String subject, Object mediaObj) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);

            if (mediaObj != null) {
                Uri mediaUri = getMediaUri(mediaObj);

                if (mediaUri != null) {
                    Log.d(TAG, "URI generated: " + mediaUri.toString());

                    // Detect media type based on file path
                    String mediaType = "image/jpeg"; // Default

                    if (mediaObj instanceof String) {
                        mediaType = detectMediaType((String) mediaObj);
                    } else if (mediaObj instanceof File) {
                        mediaType = detectMediaType(((File) mediaObj).getName());
                    }

                    Log.d(TAG, "Media type: " + mediaType);

                    shareIntent.setType(mediaType);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUri);
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    Log.d(TAG, "Intent configured successfully");
                } else {
                    Log.e(TAG, "ERROR: Media URI is null!");
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message);
                }
            } else {
                Log.d(TAG, "No media provided, sharing text only");
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, message);
            }

            Intent chooser = Intent.createChooser(shareIntent, "Share via");

            // If we have a callback, use startActivityForResult
            if (this.callback != null) {
                Activity activity = TiApplication.getAppCurrentActivity();
                TiActivitySupport activitySupport = (TiActivitySupport) activity;

                // Register this object as the result handler
                int requestCode = activitySupport.getUniqueResultCode();
                activitySupport.launchActivityForResult(chooser, requestCode, this);

                Log.d(TAG, "Share started with callback (requestCode: " + requestCode + ")");
            } else {
                // Without callback, use normal startActivity
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                TiApplication.getInstance().startActivity(chooser);
                Log.d(TAG, "Share started without callback");
            }

            Log.d(TAG, "=== SHARE INITIATED ===");

        } catch (Exception e) {
            Log.e(TAG, "ERROR in processShare: " + e.getMessage(), e);

            if (this.callback != null) {
                fireCallback(false, "Error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onResult(Activity activity, int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onResult - resultCode: " + resultCode);

        if (this.callback != null) {
            boolean success = (resultCode == Activity.RESULT_OK);
            String message = "";

            if (resultCode == Activity.RESULT_OK) {
                message = "Share completed successfully";
                Log.d(TAG, "Share successful");
            } else if (resultCode == Activity.RESULT_CANCELED) {
                message = "Share cancelled by user";
                Log.d(TAG, "Share cancelled by user");
            } else {
                message = "Share failed with code: " + resultCode;
                Log.d(TAG, "Share failed with code: " + resultCode);
            }

            fireCallback(success, message);
        }
    }

    @Override
    public void onError(Activity activity, int requestCode, Exception e) {
        Log.e(TAG, "onError: " + e.getMessage(), e);

        if (this.callback != null) {
            fireCallback(false, "Error: " + e.getMessage());
        }
    }

    private void fireCallback(boolean success, String message) {
        if (this.callback != null && this.module != null) {
            KrollDict result = new KrollDict();
            result.put("success", success);
            result.put("message", message);
            result.put(TiC.EVENT_PROPERTY_SOURCE, this.module);

            Log.d(TAG, "Firing callback - success: " + success + ", message: " + message);

            this.callback.callAsync(this.module.getKrollObject(), result);
        }
    }

    private static Uri getMediaUri(Object mediaObj) {
        try {
            File mediaFile = null;
            String authority = TiApplication.getInstance().getPackageName() + ".fileprovider";

            Log.d(TAG, "Authority: " + authority);

            // If it's a Blob
            if (mediaObj instanceof TiBlob) {
                Log.d(TAG, "Processing TiBlob");
                TiBlob blob = (TiBlob) mediaObj;

                // Try to detect extension from blob's mimetype
                String extension = ".jpg";
                String mimeType = blob.getMimeType();
                if (mimeType != null) {
                    if (mimeType.startsWith("video/")) {
                        extension = ".mp4";
                    }
                }

                File cacheDir = TiApplication.getInstance().getCacheDir();
                mediaFile = new File(cacheDir, "share_" + System.currentTimeMillis() + extension);

                Log.d(TAG, "File path: " + mediaFile.getAbsolutePath());

                FileOutputStream fos = new FileOutputStream(mediaFile);
                fos.write(blob.getBytes());
                fos.close();

                Log.d(TAG, "File created successfully. Size: " + (mediaFile.length() / 1024) + "KB");

            }
            // If it's a File object (already downloaded from URL)
            else if (mediaObj instanceof File) {
                mediaFile = (File) mediaObj;
                Log.d(TAG, "Using existing File object: " + mediaFile.getAbsolutePath());
            }
            // If it's a file path (String)
            else if (mediaObj instanceof String) {
                String path = (String) mediaObj;
                Log.d(TAG, "Processing string path: " + path);

                // Remove leading slash if exists
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                // Try MULTIPLE possible paths for Titanium resources
                String[] possiblePaths = {
                        TiApplication.getInstance().getApplicationContext().getFilesDir().getParent() + "/app/_app_/" + path,
                        TiApplication.getInstance().getApplicationContext().getFilesDir().getParent() + "/app/" + path,
                        "/android_asset/Resources/" + path,
                        "/android_asset/" + path
                };

                File resourceFile = null;
                for (String testPath : possiblePaths) {
                    File test = new File(testPath);
                    Log.d(TAG, "Trying: " + testPath + " - Exists? " + test.exists());
                    if (test.exists()) {
                        resourceFile = test;
                        Log.d(TAG, "✓ File found at: " + testPath);
                        break;
                    }
                }

                if (resourceFile != null && resourceFile.exists()) {
                    Log.d(TAG, "Resource file found!");

                    // Get file extension
                    String extension = path.substring(path.lastIndexOf("."));

                    File cacheDir = TiApplication.getInstance().getCacheDir();
                    mediaFile = new File(cacheDir, "share_" + System.currentTimeMillis() + extension);

                    java.io.FileInputStream fis = new java.io.FileInputStream(resourceFile);
                    FileOutputStream fos = new FileOutputStream(mediaFile);

                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }

                    fis.close();
                    fos.close();

                    Log.d(TAG, "File copied to cache. Size: " + (mediaFile.length() / 1024) + "KB");
                } else {
                    Log.d(TAG, "Trying to load as direct asset...");

                    try {
                        android.content.res.AssetManager assets = TiApplication.getInstance().getAssets();
                        java.io.InputStream is = assets.open("Resources/" + path);

                        String extension = path.substring(path.lastIndexOf("."));

                        File cacheDir = TiApplication.getInstance().getCacheDir();
                        mediaFile = new File(cacheDir, "share_" + System.currentTimeMillis() + extension);

                        FileOutputStream fos = new FileOutputStream(mediaFile);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }

                        is.close();
                        fos.close();

                        Log.d(TAG, "✓ Asset loaded successfully! Size: " + (mediaFile.length() / 1024) + "KB");

                    } catch (Exception assetEx) {
                        Log.e(TAG, "Failed to load as asset: " + assetEx.getMessage());

                        Log.d(TAG, "Last attempt: applicationDataDirectory");
                        mediaFile = new File(TiApplication.getInstance().getFilesDir(), path);
                        Log.d(TAG, "Path: " + mediaFile.getAbsolutePath() + " - Exists? " + mediaFile.exists());
                    }
                }
            }

            if (mediaFile != null && mediaFile.exists()) {
                // Check file size
                long fileSizeKB = mediaFile.length() / 1024;
                long fileSizeMB = fileSizeKB / 1024;

                Log.d(TAG, "✓✓✓ Final file exists: " + mediaFile.getAbsolutePath());
                Log.d(TAG, "Size: " + fileSizeKB + "KB (" + fileSizeMB + "MB)");
                Log.d(TAG, "Can read? " + mediaFile.canRead());

                if (mediaFile.length() > MAX_FILE_SIZE) {
                    Log.e(TAG, "⚠️ WARNING: File size (" + fileSizeMB + "MB) exceeds recommended limit of " + (MAX_FILE_SIZE / (1024 * 1024)) + "MB");
                    Log.e(TAG, "Some apps may fail to handle this file size");
                }

                Uri uri = FileProvider.getUriForFile(
                        TiApplication.getInstance(),
                        authority,
                        mediaFile
                );

                Log.d(TAG, "✓✓✓ FileProvider URI generated: " + uri.toString());
                return uri;
            } else {
                Log.e(TAG, "✗✗✗ ERROR: File does not exist or is null!");
                if (mediaFile != null) {
                    Log.e(TAG, "Failed path: " + mediaFile.getAbsolutePath());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ERROR processing media: " + e.getMessage(), e);
            e.printStackTrace();
        }

        return null;
    }
}