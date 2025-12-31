package ti.android.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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
            String subject = TiConvert.toString(params.get("subject"), "Compartilhar");
            Object imageObj = params.get("image");
            Object callbackObj = params.get("callback");

            // Armazenar callback se fornecido
            if (callbackObj != null && callbackObj instanceof KrollFunction) {
                this.callback = (KrollFunction) callbackObj;
            }

            Log.d(TAG, "=== INÍCIO DO COMPARTILHAMENTO ===");
            Log.d(TAG, "Mensagem: " + message);
            Log.d(TAG, "Tipo do objeto imagem: " + (imageObj != null ? imageObj.getClass().getName() : "null"));
            Log.d(TAG, "Callback fornecido: " + (this.callback != null));

            Intent shareIntent = new Intent(Intent.ACTION_SEND);

            if (imageObj != null) {
                Uri imageUri = getImageUri(imageObj);

                if (imageUri != null) {
                    Log.d(TAG, "URI gerada: " + imageUri.toString());

                    shareIntent.setType("image/jpeg");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    Log.d(TAG, "Intent configurado com sucesso");
                } else {
                    Log.e(TAG, "ERRO: URI da imagem é null!");
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, message);
                }
            } else {
                Log.d(TAG, "Nenhuma imagem fornecida, compartilhando apenas texto");
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, message);
            }

            Intent chooser = Intent.createChooser(shareIntent, "Compartilhar via");

            // Se temos callback, usar startActivityForResult
            if (this.callback != null) {
                Activity activity = TiApplication.getAppCurrentActivity();
                TiActivitySupport activitySupport = (TiActivitySupport) activity;

                // Registrar este objeto como handler do resultado
                int requestCode = activitySupport.getUniqueResultCode();
                activitySupport.launchActivityForResult(chooser, requestCode, this);

                Log.d(TAG, "Compartilhamento iniciado com callback (requestCode: " + requestCode + ")");
            } else {
                // Sem callback, usar startActivity normal
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                TiApplication.getInstance().startActivity(chooser);
                Log.d(TAG, "Compartilhamento iniciado sem callback");
            }

            Log.d(TAG, "=== COMPARTILHAMENTO INICIADO ===");

        } catch (Exception e) {
            Log.e(TAG, "ERRO FATAL ao compartilhar: " + e.getMessage(), e);

            // Chamar callback com erro se disponível
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
                Log.d(TAG, "Compartilhamento bem-sucedido");
            } else if (resultCode == Activity.RESULT_CANCELED) {
                message = "Share cancelled by user";
                Log.d(TAG, "Compartilhamento cancelado pelo usuário");
            } else {
                message = "Share failed with code: " + resultCode;
                Log.d(TAG, "Compartilhamento falhou com código: " + resultCode);
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

            Log.d(TAG, "Disparando callback - success: " + success + ", message: " + message);

            this.callback.callAsync(this.module.getKrollObject(), result);
        }
    }

    private static Uri getImageUri(Object imageObj) {
        try {
            File imageFile = null;
            String authority = TiApplication.getInstance().getPackageName() + ".fileprovider";

            Log.d(TAG, "Authority: " + authority);

            // Se for um Blob
            if (imageObj instanceof TiBlob) {
                Log.d(TAG, "Processando TiBlob");
                TiBlob blob = (TiBlob) imageObj;

                File cacheDir = TiApplication.getInstance().getCacheDir();
                imageFile = new File(cacheDir, "share_" + System.currentTimeMillis() + ".jpg");

                Log.d(TAG, "Caminho do arquivo: " + imageFile.getAbsolutePath());

                FileOutputStream fos = new FileOutputStream(imageFile);
                fos.write(blob.getBytes());
                fos.close();

                Log.d(TAG, "Arquivo criado com sucesso. Tamanho: " + imageFile.length() + " bytes");

            }
            // Se for um caminho de arquivo (String)
            else if (imageObj instanceof String) {
                String path = (String) imageObj;
                Log.d(TAG, "Processando caminho de string: " + path);

                // Remover barra inicial se existir
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }

                // Tentar MÚLTIPLOS caminhos possíveis para recursos do Titanium
                String[] possiblePaths = {
                        TiApplication.getInstance().getApplicationContext().getFilesDir().getParent() + "/app/_app_/" + path,
                        TiApplication.getInstance().getApplicationContext().getFilesDir().getParent() + "/app/" + path,
                        "/android_asset/Resources/" + path,
                        "/android_asset/" + path
                };

                File resourceFile = null;
                for (String testPath : possiblePaths) {
                    File test = new File(testPath);
                    Log.d(TAG, "Tentando: " + testPath + " - Existe? " + test.exists());
                    if (test.exists()) {
                        resourceFile = test;
                        Log.d(TAG, "✓ Arquivo encontrado em: " + testPath);
                        break;
                    }
                }

                if (resourceFile != null && resourceFile.exists()) {
                    Log.d(TAG, "Arquivo de recursos encontrado!");

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

                    Log.d(TAG, "Arquivo copiado para cache. Tamanho: " + imageFile.length() + " bytes");
                } else {
                    Log.d(TAG, "Tentando carregar como asset direto...");

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

                        Log.d(TAG, "✓ Asset carregado com sucesso! Tamanho: " + imageFile.length() + " bytes");

                    } catch (Exception assetEx) {
                        Log.e(TAG, "Falha ao carregar como asset: " + assetEx.getMessage());

                        Log.d(TAG, "Última tentativa: applicationDataDirectory");
                        imageFile = new File(TiApplication.getInstance().getFilesDir(), path);
                        Log.d(TAG, "Caminho: " + imageFile.getAbsolutePath() + " - Existe? " + imageFile.exists());
                    }
                }
            }

            if (imageFile != null && imageFile.exists()) {
                Log.d(TAG, "✓✓✓ Arquivo final existe: " + imageFile.getAbsolutePath());
                Log.d(TAG, "Tamanho: " + imageFile.length() + " bytes");
                Log.d(TAG, "Pode ler? " + imageFile.canRead());

                Uri uri = FileProvider.getUriForFile(
                        TiApplication.getInstance(),
                        authority,
                        imageFile
                );

                Log.d(TAG, "✓✓✓ URI FileProvider gerada: " + uri.toString());
                return uri;
            } else {
                Log.e(TAG, "✗✗✗ ERRO: Arquivo não existe ou é null!");
                if (imageFile != null) {
                    Log.e(TAG, "Caminho que falhou: " + imageFile.getAbsolutePath());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ERRO ao processar imagem: " + e.getMessage(), e);
            e.printStackTrace();
        }

        return null;
    }
}