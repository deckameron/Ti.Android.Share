# Ti.Android.Share

A native Android module for Titanium SDK that provides seamless image and text sharing using Android's FileProvider API.

![Titanium](https://img.shields.io/badge/Titanium-13.0+-red.svg) ![Platform](https://img.shields.io/badge/platform-Android-lightgrey.svg) ![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Maintained](https://img.shields.io/badge/Maintained-Yes-green.svg)

## Overview

This module solves a critical limitation in Titanium SDK when sharing images on Android 7.0+ (API 24+). Modern Android versions require the use of `content://` URIs through FileProvider instead of `file://` URIs for security reasons. While Titanium SDK provides intent-based sharing capabilities, it lacks native FileProvider support, making image sharing problematic with apps like WhatsApp, Telegram, and email clients.

`ti.android.share` bridges this gap by providing a simple JavaScript API that handles all the complexity of FileProvider configuration, URI generation, and secure file sharing.

## Features

- Share images from URLs, Resources, Blobs, or file paths
- Automatic image download from remote URLs
- Automatic FileProvider URI generation
- Support for text and image combination sharing
- Optional callback to handle share results (success, cancelled, or error)
- Works with WhatsApp, Telegram, Email, and all Android share targets
- Compatible with Titanium SDK 13.x and modern Android versions

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

ShareModule.share({
    message: "Check out this amazing content!",
    subject: "Sharing from my app",
    image: "/images/photo.jpg"
});
```

### Share image from Resources

```javascript
ShareModule.share({
    message: "Look at this picture!",
    image: "/images/my-image.png"
});
```

### Share image from Blob

```javascript
const imageView = Ti.UI.createImageView({
    image: 'https://example.com/image.jpg'
});

// After image loads
imageView.addEventListener('load', function() {
    ShareModule.share({
        message: "Sharing dynamically loaded image",
        image: imageView.toBlob()
    });
});
```

### Share image from URL

The module will automatically download the image in the background before sharing. If the download fails, it will share text only (or notify via callback if provided).

```javascript
ShareModule.share({
    message: "Check out this amazing photo!",
    subject: "Photo from the web",
    image: "https://www.example.com/photos/sunset.jpg"
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
  - `image` (String|TiBlob, optional): Image to share. Can be:
    - **URL** (e.g., `"https://example.com/photo.jpg"`) - Will be downloaded automatically
    - Path to resource file (e.g., `/images/photo.jpg`)
    - Path from applicationDataDirectory
    - TiBlob object (from `imageView.toBlob()`, camera, etc.)
  - `callback` (Function, optional): Callback function to receive share result

**Callback object properties:**

- `success` (Boolean): `true` if share completed successfully, `false` if cancelled or failed
- `message` (String): Descriptive message about the share result:
  - `"Share completed successfully"` - User completed the share action
  - `"Share cancelled by user"` - User cancelled the share dialog
  - `"Error: [description]"` - An error occurred during sharing

**Returns:** void

**Example:**

```javascript
ShareModule.share({
    message: "Hello World",
    subject: "Greeting",
    image: myImageBlob,
    callback: function(e) {
        console.log("Success: " + e.success);
        console.log("Message: " + e.message);
    }
});
```

## How it works

The module handles the following automatically:

1. **URL Detection**: Automatically detects if the image parameter is a URL (starts with `http://` or `https://`)

2. **Image Download**: For URL images:
   - Downloads the image asynchronously in the background
   - Uses a 15-second timeout for connection and read operations
   - Saves to the cache directory
   - If download fails, shares text only or notifies via callback

3. **Resource location**: For local files, attempts to find images in multiple locations:
   - Application resources (`/app/_app_/Resources/`)
   - Android assets
   - Application data directory
   - Cache directory

4. **File preparation**: For resource files and Blobs:
   - Copies the file to the cache directory
   - Ensures the file is accessible by the FileProvider

5. **URI generation**: Creates a secure `content://` URI using Android's FileProvider

6. **Permission granting**: Adds `FLAG_GRANT_READ_URI_PERMISSION` so receiving apps can access the file

7. **Intent creation**: Builds a proper `ACTION_SEND` intent with all necessary flags

8. **Result handling**: When a callback is provided, uses `startActivityForResult` to capture the share outcome and notify your application

## Troubleshooting

### URL image download issues

If images from URLs fail to download:

- Ensure the URL is publicly accessible (not behind authentication)
- Check that the URL points directly to an image file (ends in .jpg, .png, etc.)

### Callback not firing

The callback relies on Android's activity result system. Note that:

- `success: true` means the user selected an app and the share intent was delivered
- `success: false` means the user cancelled the share dialog or an error occurred
- Some apps may not properly report back to the activity result system
- The callback indicates the share was initiated, not necessarily that the recipient app completed the action

### Image not found error

If you see logs indicating the image wasn't found, verify:

- The image path is correct (use `/images/photo.jpg`, not `images/photo.jpg`)
- The image exists in your `Resources` folder or specified directory
- Check the `adb logcat` output for detailed path information

### WhatsApp/Telegram not showing image

Ensure:

- The image file is a valid JPEG or PNG
- The file size is reasonable (< 5MB recommended)
- Your app has necessary permissions in `tiapp.xml`:

```xml
<!-- Necessária APENAS se você ler imagens de locais externos ao cache -->
<!-- Only needed if you read images from external sources or cache -->
<!-- Android 6 to 12 -->
<uses-permission 
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>

<!-- Android 13+ (replaced READ_EXTERNAL_STORAGE) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
