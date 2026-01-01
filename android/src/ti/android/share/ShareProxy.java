package ti.android.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
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

public class ShareProxy implements TiActivityResultHandler {

    private static final String TAG = "ShareProxy";
    private static final int REQUEST_CODE_SHARE = 1001;

    private KrollFunction callback;
    private KrollModule module;

    public static void share(HashMap params, KrollModule module) {
        ShareProxy proxy = new ShareProxy();
        proxy.module = module;
        proxy.executeShare(params);
    }

    private void executeShare(HashMap params) {
        try {
            String message = TiConvert.toString(params.get("message"), "");
            String subject = TiConvert.toString(params.get("subject"), "Share");
            Object imageObj = params.get("image");
            Object callbackObj = params.get("callback");

            // Store callback if provided
            if (callbackObj != null && callbackObj instanceof KrollFunction) {
                this.callback = (KrollFunction) callbackObj;
            }

            Log.d(TAG, "=== SHARE START ===");
            Log.d(TAG, "Message: " + message);
            Log.d(TAG, "Image object type: " + (imageObj != null ? imageObj.getClass().getName() : "null"));
            Log.d(TAG, "Callback provided: " + (this.callback != null));

            // Check if image is a URL
            if (imageObj instanceof String && isUrl((String) imageObj)) {
                String imageUrl = (String) imageObj;
                Log.d(TAG, "Detected URL image: " + imageUrl);

                // Download image asynchronously
                new ImageDownloadTask(message, subject).execute(imageUrl);

            } else {
                // Process normally (local file or blob)
                processShare(message, subject, imageObj);
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

    private void processShare(String message, String subject, Object imageObj) {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);

            if (imageObj != null) {
                Uri imageUri = getImageUri(imageObj);

                if (imageUri != null) {
                    Log.d(TAG, "URI generated: " + imageUri.toString());

                    shareIntent.setType("image/jpeg");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    Log.d(TAG, "Intent configured successfully");
                } else {
                    Log.e(TAG, "ERROR: Image URI is null!");
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message);
                }
            } else {
                Log.d(TAG, "No image provided, sharing text only");
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

    private static Uri getImageUri(Object imageObj) {
        try {
            File imageFile = null;
            String authority = TiApplication.getInstance().getPackageName() + ".fileprovider";

            Log.d(TAG, "Authority: " + authority);

            // If it's a Blob
            if (imageObj instanceof TiBlob) {
                Log.d(TAG, "Processing TiBlob");
                TiBlob blob = (TiBlob) imageObj;

                File cacheDir = TiApplication.getInstance().getCacheDir();
                imageFile = new File(cacheDir, "share_" + System.currentTimeMillis() + ".jpg");

                Log.d(TAG, "File path: " + imageFile.getAbsolutePath());

                FileOutputStream fos = new FileOutputStream(imageFile);
                fos.write(blob.getBytes());
                fos.close();

                Log.d(TAG, "File created successfully. Size: " + imageFile.length() + " bytes");

            }
            // If it's a File object (already downloaded from URL)
            else if (imageObj instanceof File) {
                imageFile = (File) imageObj;
                Log.d(TAG, "Using existing File object: " + imageFile.getAbsolutePath());
            }
            // If it's a file path (String)
            else if (imageObj instanceof String) {
                String path = (String) imageObj;
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

                    File cacheDir = TiApplication.getInstance().getCacheDir();
                    imageFile = new File(cacheDir, "share_" + System.currentTimeMillis() + ".jpg");

                    java.io.FileInputStream fis = new java.io.FileInputStream(resourceFile);
                    FileOutputStream fos = new FileOutputStream(imageFile);

                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }

                    fis.close();
                    fos.close();

                    Log.d(TAG, "File copied to cache. Size: " + imageFile.length() + " bytes");
                } else {
                    Log.d(TAG, "Trying to load as direct asset...");

                    try {
                        android.content.res.AssetManager assets = TiApplication.getInstance().getAssets();
                        java.io.InputStream is = assets.open("Resources/" + path);

                        File cacheDir = TiApplication.getInstance().getCacheDir();
                        imageFile = new File(cacheDir, "share_" + System.currentTimeMillis() + ".jpg");

                        FileOutputStream fos = new FileOutputStream(imageFile);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }

                        is.close();
                        fos.close();

                        Log.d(TAG, "✓ Asset loaded successfully! Size: " + imageFile.length() + " bytes");

                    } catch (Exception assetEx) {
                        Log.e(TAG, "Failed to load as asset: " + assetEx.getMessage());

                        Log.d(TAG, "Last attempt: applicationDataDirectory");
                        imageFile = new File(TiApplication.getInstance().getFilesDir(), path);
                        Log.d(TAG, "Path: " + imageFile.getAbsolutePath() + " - Exists? " + imageFile.exists());
                    }
                }
            }

            if (imageFile != null && imageFile.exists()) {
                Log.d(TAG, "✓✓✓ Final file exists: " + imageFile.getAbsolutePath());
                Log.d(TAG, "Size: " + imageFile.length() + " bytes");
                Log.d(TAG, "Can read? " + imageFile.canRead());

                Uri uri = FileProvider.getUriForFile(
                        TiApplication.getInstance(),
                        authority,
                        imageFile
                );

                Log.d(TAG, "✓✓✓ FileProvider URI generated: " + uri.toString());
                return uri;
            } else {
                Log.e(TAG, "✗✗✗ ERROR: File does not exist or is null!");
                if (imageFile != null) {
                    Log.e(TAG, "Failed path: " + imageFile.getAbsolutePath());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ERROR processing image: " + e.getMessage(), e);
            e.printStackTrace();
        }

        return null;
    }

    /**
     * AsyncTask to download image from URL in background
     */
    private class ImageDownloadTask extends AsyncTask<String, Void, File> {

        private String message;
        private String subject;
        private String errorMessage;

        public ImageDownloadTask(String message, String subject) {
            this.message = message;
            this.subject = subject;
        }

        @Override
        protected File doInBackground(String... urls) {
            String imageUrl = urls[0];
            HttpURLConnection connection = null;

            try {
                Log.d(TAG, "Starting image download from: " + imageUrl);

                URL url = new URL(imageUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestMethod("GET");
                connection.setDoInput(true);
                connection.connect();

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream input = connection.getInputStream();

                    // Create temporary file
                    File cacheDir = TiApplication.getInstance().getCacheDir();
                    File imageFile = new File(cacheDir, "share_url_" + System.currentTimeMillis() + ".jpg");

                    FileOutputStream output = new FileOutputStream(imageFile);

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalBytes = 0;

                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }

                    output.close();
                    input.close();

                    Log.d(TAG, "✓ Image downloaded successfully. Size: " + totalBytes + " bytes");
                    Log.d(TAG, "Saved to: " + imageFile.getAbsolutePath());

                    return imageFile;

                } else {
                    errorMessage = "HTTP error code: " + responseCode;
                    Log.e(TAG, errorMessage);
                    return null;
                }

            } catch (Exception e) {
                errorMessage = "Download failed: " + e.getMessage();
                Log.e(TAG, errorMessage, e);
                return null;

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(File imageFile) {
            if (imageFile != null && imageFile.exists()) {
                Log.d(TAG, "Download completed, proceeding with share");
                processShare(message, subject, imageFile);
            } else {
                Log.e(TAG, "Download failed, sharing text only");

                // If callback exists, notify about download failure
                if (callback != null) {
                    fireCallback(false, errorMessage != null ? errorMessage : "Failed to download image");
                } else {
                    // Share text only if no callback
                    processShare(message, subject, null);
                }
            }
        }
    }
}