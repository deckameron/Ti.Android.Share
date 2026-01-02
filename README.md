# Ti.Android.Share

A native Android module for Titanium SDK that provides seamless image, video and text sharing using Android's FileProvider API.

![Titanium](https://img.shields.io/badge/Titanium-13.0+-red.svg) ![Platform](https://img.shields.io/badge/platform-Android-lightgrey.svg) ![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Maintained](https://img.shields.io/badge/Maintained-Yes-green.svg)

## Overview

This module solves a limitation in Titanium SDK when sharing images and videos on Android 7.0+ (API 24+). Modern Android versions require the use of  `content://`  URIs through FileProvider instead of  `file://`  URIs for security reasons. While Titanium SDK provides intent-based sharing capabilities, it lacks native FileProvider support, making media sharing problematic with apps like WhatsApp, Telegram, and email clients.

`ti.android.share`  bridges this gap by providing a simple JavaScript API that handles all the complexity of FileProvider configuration, URI generation, and secure file sharing for both images and videos.

## Features

-   Share text, images and videos from URLs, Resources, Blobs, or file paths
-   Automatic media type detection (image vs video)
-   Automatic media download from remote URLs
-   Support for popular video formats: MP4, MOV, AVI, 3GP, MKV, WEBM, FLV, M4V
-   Automatic FileProvider URI generation
-   Support for text and media combination sharing
-   File size validation and warnings (100MB limit for downloads)
-   Optional callback to handle share results (success, cancelled, or error)
-   Works with WhatsApp, Telegram, Email, and all Android share targets
-   Compatible with Titanium SDK 13.x and modern Android versions

## Requirements

- Titanium SDK 13.0.0 or higher
- Android target SDK 24 or higher

## Installation

### Download the module

Download the latest release from the [releases page](https://github.com/deckameron/Ti.Android.Share/releases) or build from source.

### Add to your project

1. Copy the module ZIP file to your project root directory
2. Add the module to your `tiapp.xml`:

```xml
<modules>
    <module platform="android">ti.android.share</module>
</modules>
```

### FileProvider configuration

The module automatically configures the FileProvider, but ensure your `tiapp.xml` has a valid application ID:

```xml
<id>com.yourcompany.yourapp</id>
```

The module will use `com.yourcompany.yourapp.fileprovider` as the FileProvider authority.

## Usage

### Basic example

```javascript
const ShareModule = require('ti.android.share');

// Share media
ShareModule.share({
    message: "Check out this amazing content!",
    subject: "Sharing from my app",
    image: "/images/photo.jpg"
});

// Share video
ShareModule.share({
    message: "Watch this!",
    subject: "Cool video",
    media: "/videos/demo.mp4"
});
```

### Share image from Blob

```javascript
// Share image blob
const imageView = Ti.UI.createImageView({
    media: 'https://example.com/image.jpg'
});

imageView.addEventListener('load', function() {
    ShareModule.share({
        message: "Sharing dynamically loaded image",
        media: imageView.toBlob()
    });
});
```

### Share video from File System
```javascript
// Share video file
const videoFile = Ti.Filesystem.getFile(Ti.Filesystem.applicationDataDirectory, 'video.mp4');
ShareModule.share({
    message: "My video",
    media: videoFile.read()
});
```

### Share media from URL

The module will automatically download the media in the background before sharing. If the download fails, it will share text only (or notify via callback if provided).

```javascript
// Share image from url
ShareModule.share({
    message: "Check out this amazing photo!",
    subject: "Photo from the web",
    image: "https://www.example.com/photos/sunset.jpg"
});

// Share video from url
ShareModule.share({
    message: "Watch this awesome video!",
    subject: "Video to share",
    media: "https://www.example.com/videos/demo.mp4",
    callback: function(e) {
        if (e.success) {
            Ti.API.info("Video shared successfully!");
        } else {
            Ti.API.error("Share failed: " + e.message);
        }
    }
});
```

### Share text only

```javascript
ShareModule.share({
    message: "Just sharing some text",
    subject: "Important message"
});
```

### Share with callback

```javascript
ShareModule.share({
    message: "Check this out!",
    image: "/images/photo.jpg",
    subject: "Amazing content",
    callback: function(e) {
        if (e.success) {
            Ti.API.info("Share completed: " + e.message);
            // Handle success - maybe track analytics
        } else {
            Ti.API.error("Share failed: " + e.message);
            // Handle cancellation or error
        }
    }
});
```

## API Reference

### Methods

#### `share(options)`

Opens the Android share dialog with the specified content.

**Parameters:**

- `options` (Object): Configuration object with the following properties:
  - `message` (String, optional): Text content to share
  - `subject` (String, optional): Subject line for sharing (used by email apps)
  -  `media` (String|TiBlob, optional): Media file to share (images or videos). Can be: -  **URL** (e.g., `"https://example.com/video.mp4"`) - Will be downloaded automatically - Path to resource file (e.g., `/videos/demo.mp4` or `/images/photo.jpg`) - Path from applicationDataDirectory - TiBlob object (from file read, camera, video recorder, etc.) 
  - `image` (String|TiBlob, optional): **Deprecated** - Use `media` instead. Still supported for backward compatibility
  - `callback` (Function, optional): Callback function to receive share result

**Supported Media Formats:** 
-  **Images**: JPG, JPEG, PNG, GIF, WEBP 
-   **Videos**: MP4, MOV, AVI, 3GP, MKV, WEBM, FLV, M4V

The module automatically detects the media type based on file extension.

**Callback object properties:**

- `success` (Boolean): `true` if share completed successfully, `false` if cancelled or failed
- `message` (String): Descriptive message about the share result:
  - `"Share completed successfully"` - User completed the share action
  - `"Share cancelled by user"` - User cancelled the share dialog
  - `"Error: [description]"` - An error occurred during sharing

**Returns:** void

## How it works

The module handles the following automatically:

1. **Media Type Detection**: Automatically detects whether the file is an image or video based on file extension
   - Images: JPG, PNG, GIF, WEBP
   - Videos: MP4, MOV, AVI, 3GP, MKV, WEBM, FLV, M4V

2. **URL Detection**: Automatically detects if the media parameter is a URL (starts with `http://` or `https://`)

3. **Media Download**: For URL media:
   - Downloads the file asynchronously in the background
   - Uses a 15-second timeout for images, 30-second timeout for videos
   - Validates file size (100MB limit for downloads)
   - Saves to the cache directory
   - If download fails, shares text only or notifies via callback

4. **Resource location**: For local files, attempts to find media in multiple locations:
   - Application resources (`/app/_app_/Resources/`)
   - Android assets
   - Application data directory
   - Cache directory

5. **File preparation**: For resource files and Blobs:
   - Copies the file to the cache directory
   - Preserves original file extension
   - Ensures the file is accessible by the FileProvider

6. **URI generation**: Creates a secure `content://` URI using Android's FileProvider

7. **Permission granting**: Adds `FLAG_GRANT_READ_URI_PERMISSION` so receiving apps can access the file

8. **Intent creation**: Builds a proper `ACTION_SEND` intent with correct MIME type (`image/*` or `video/*`)

9. **Result handling**: When a callback is provided, uses `startActivityForResult` to capture the share outcome and notify your application

## Troubleshooting

### Video sharing issues

**File size limitations:**

Different apps have different size limits for videos:
- **WhatsApp**: ~16MB (approximately 2 minutes)
- **Telegram**: 2GB (best for large videos)
- **Email**: 10-25MB (depends on email provider)
- **Instagram**: 60 seconds maximum duration
- **Facebook**: 4GB / 240 minutes

**Recommendations:**
- Keep videos under 16MB for maximum compatibility
- Use Telegram for large video files
- The module logs warnings when files exceed 100MB
- Consider compressing videos before sharing

**Common issues:**
- "File too large" error: Reduce video resolution or duration
- "Format not supported": Ensure video is in MP4, MOV, or 3GP format
- Download timeout: Videos larger than 50MB may timeout (30-second limit)

### URL media download issues

If media from URLs fails to download:

- Ensure the URL is publicly accessible (not behind authentication)
- Check that the URL points directly to a media file (ends in .jpg, .mp4, etc.)
- Verify your app has INTERNET permission in `tiapp.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET"/>
```

- The module uses a 15-second timeout for images and 30-second timeout for videos
- Files larger than 100MB will be rejected during download
- Check `adb logcat` for HTTP response codes and error messages
- If using HTTPS, ensure the server has valid SSL certificates

### Callback not firing

The callback relies on Android's activity result system. Note that:

- `success: true` means the user selected an app and the share intent was delivered
- `success: false` means the user cancelled the share dialog or an error occurred
- Some apps may not properly report back to the activity result system
- The callback indicates the share was initiated, not necessarily that the recipient app completed the action

### Media not found error

If you see logs indicating the media wasn't found, verify:

- The media path is correct (use `/images/photo.jpg` or `/videos/demo.mp4`, not `images/photo.jpg`)
- The file exists in your `Resources` folder or specified directory
- Check the `adb logcat` output for detailed path information
- For videos, ensure the file extension is included (.mp4, .mov, etc.)

### WhatsApp/Telegram not showing media

Ensure:

- The file is a valid image (JPEG, PNG) or video (MP4, MOV, 3GP)
- File size is within app limits (WhatsApp: ~16MB for videos, Telegram: 2GB)
- Your app has necessary permissions in `tiapp.xml`:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
