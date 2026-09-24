package com.example.privatevault;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.*;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {

    private File vault;
    private RecyclerView grid;
    private ArrayList<File> media = new ArrayList<>();
    private MediaAdapter adapter;
    private ActivityResultLauncher<String[]> picker;

    private View lockOverlay;
    private EditText pinInput;
    private Button unlockButton;
    private Button fingerprintButton;

    private boolean unlocked = false;
    private boolean pickingFile = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        vault = new File(getFilesDir(), "vault");

        if (!vault.exists()) {
            vault.mkdirs();
        }

        grid = findViewById(R.id.grid);

        grid.setLayoutManager(new GridLayoutManager(this, 3));

        adapter = new MediaAdapter();
        grid.setAdapter(adapter);

        /*
         * IMPORTANT:
         * Media grid is hidden until authentication succeeds.
         */
        grid.setVisibility(View.GONE);

        setupPicker();
        setupButtons();

        createLockScreen();

        showLockScreen();
    }

    private void setupPicker() {

        picker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {

                    pickingFile = false;

                    if (!unlocked) {
                        return;
                    }

                    if (uris != null) {

                        for (Uri uri : uris) {
                            copyToVault(uri);
                        }

                        load();
                    }
                }
        );
    }

    private void setupButtons() {

        View add = findViewById(R.id.add);

        if (add != null) {

            add.setOnClickListener(v -> {

                if (!unlocked) {
                    return;
                }

                pickingFile = true;

                picker.launch(
                        new String[]{
                                "image/*",
                                "video/*"
                        }
                );
            });
        }

        View settings = findViewById(R.id.settings);

        if (settings != null) {

            settings.setOnClickListener(v -> {

                if (unlocked) {
                    settings();
                }
            });
        }
    }

    /*
     * Creates the PIN screen programmatically.
     * Therefore activity_main.xml does not need to be changed.
     */
    private void createLockScreen() {

        FrameLayout root =
                findViewById(android.R.id.content);

        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(40, 40, 40, 40);

        box.setBackgroundColor(
                0xFFFFFFFF
        );

        TextView icon =
                new TextView(this);

        icon.setText("🔐");
        icon.setTextSize(60);
        icon.setGravity(Gravity.CENTER);

        box.addView(
                icon,
                new LinearLayout.LayoutParams(
                        -1,
                        100
                )
        );

        TextView title =
                new TextView(this);

        title.setText("Private Vault");
        title.setTextSize(30);
        title.setTextColor(0xFF111111);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);

        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        titleParams.setMargins(0, 15, 0, 8);

        box.addView(title, titleParams);

        TextView message =
                new TextView(this);

        message.setText(
                "Enter your PIN to unlock"
        );

        message.setTextSize(16);
        message.setTextColor(0xFF555555);
        message.setGravity(Gravity.CENTER);

        box.addView(
                message,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        pinInput =
                new EditText(this);

        pinInput.setHint("PIN");
        pinInput.setTextSize(22);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setInputType(
                InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );

        pinInput.setSingleLine(true);
        pinInput.setMaxLength(8);

        LinearLayout.LayoutParams pinParams =
                new LinearLayout.LayoutParams(
                        230,
                        60
                );

        pinParams.setMargins(
                0, 25, 0, 12
        );

        box.addView(
                pinInput,
                pinParams
        );

        unlockButton =
                new Button(this);

        unlockButton.setText("UNLOCK");

        box.addView(
                unlockButton,
                new LinearLayout.LayoutParams(
                        230,
                        55
                )
        );

        fingerprintButton =
                new Button(this);

        fingerprintButton.setText(
                "👆 Fingerprint"
        );

        fingerprintButton.setVisibility(
                View.GONE
        );

        LinearLayout.LayoutParams fingerParams =
                new LinearLayout.LayoutParams(
                        230,
                        55
                );

        fingerParams.setMargins(
                0, 8, 0, 0
        );

        box.addView(
                fingerprintButton,
                fingerParams
        );

        lockOverlay = box;

        root.addView(
                lockOverlay,
                new FrameLayout.LayoutParams(
                        -1,
                        -1
                )
        );

        unlockButton.setOnClickListener(
                v -> verifyPin()
        );

        fingerprintButton.setOnClickListener(
                v -> biometricUnlock()
        );
    }

    private boolean hasPin() {

        String hash =
                getSharedPreferences(
                        "secure",
                        MODE_PRIVATE
                ).getString(
                        "pin_hash",
                        null
                );

        String oldPin =
                getSharedPreferences(
                        "secure",
                        MODE_PRIVATE
                ).getString(
                        "pin",
                        null
                );

        return hash != null || oldPin != null;
    }

    private void showLockScreen() {

        unlocked = false;

        grid.setVisibility(
                View.GONE
        );

        lockOverlay.setVisibility(
                View.VISIBLE
        );

        pinInput.setText("");

        if (!hasPin()) {

            unlockButton.setText(
                    "CREATE PIN"
            );

        } else {

            unlockButton.setText(
                    "UNLOCK"
            );

            showFingerprintIfAvailable();
        }
    }

    private void verifyPin() {

        String entered =
                pinInput.getText()
                        .toString();

        if (entered.length() < 4 ||
                entered.length() > 8) {

            pinInput.setError(
                    "PIN must be 4-8 digits"
            );

            return;
        }

        String savedHash =
                getSharedPreferences(
                        "secure",
                        MODE_PRIVATE
                ).getString(
                        "pin_hash",
                        null
                );

        String oldPin =
                getSharedPreferences(
                        "secure",
                        MODE_PRIVATE
                ).getString(
                        "pin",
                        null
                );

        /*
         * First-time PIN creation.
         */
        if (savedHash == null &&
                oldPin == null) {

            getSharedPreferences(
                    "secure",
                    MODE_PRIVATE
            ).edit()
                    .putString(
                            "pin_hash",
                            sha256(entered)
                    )
                    .apply();

            Toast.makeText(
                    this,
                    "PIN created successfully",
                    Toast.LENGTH_SHORT
            ).show();

            unlockVault();

            return;
        }

        /*
         * New secure PIN.
         */
        if (savedHash != null &&
                savedHash.equals(
                        sha256(entered)
                )) {

            unlockVault();

            return;
        }

        /*
         * Compatibility with your old V2 PIN.
         */
        if (oldPin != null &&
                oldPin.equals(entered)) {

            /*
             * Convert old PIN to hashed PIN.
             */
            getSharedPreferences(
                    "secure",
                    MODE_PRIVATE
            ).edit()
                    .putString(
                            "pin_hash",
                            sha256(entered)
                    )
                    .remove("pin")
                    .apply();

            unlockVault();

            return;
        }

        pinInput.setError(
                "Wrong PIN"
        );

        pinInput.setText("");

        Toast.makeText(
                this,
                "Wrong PIN",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void unlockVault() {

        unlocked = true;

        lockOverlay.setVisibility(
                View.GONE
        );

        grid.setVisibility(
                View.VISIBLE
        );

        load();

        InputMethodManager imm =
                (InputMethodManager)
                        getSystemService(
                                INPUT_METHOD_SERVICE
                        );

        if (imm != null) {

            imm.hideSoftInputFromWindow(
                    pinInput.getWindowToken(),
                    0
            );
        }
    }

    private void lockVault() {

        if (!unlocked) {
            return;
        }

        unlocked = false;

        grid.setVisibility(
                View.GONE
        );

        lockOverlay.setVisibility(
                View.VISIBLE
        );

        pinInput.setText("");

        unlockButton.setText(
                "UNLOCK"
        );

        Toast.makeText(
                this,
                "Vault locked",
                Toast.LENGTH_SHORT
        ).show();
    }

    /*
     * Android Back:
     * If vault is open, first Back locks it.
     * Second Back exits the app.
     */
    @Override
    public void onBackPressed() {

        if (unlocked) {

            lockVault();

            return;
        }

        super.onBackPressed();
    }

    /*
     * When app goes to background, lock it.
     * File picker is excluded.
     */
    @Override
    protected void onStop() {

        super.onStop();

        if (unlocked &&
                !pickingFile) {

            lockVault();
        }
    }

    private void showFingerprintIfAvailable() {

        if (Build.VERSION.SDK_INT < 23) {

            fingerprintButton.setVisibility(
                    View.GONE
            );

            return;
        }

        int result =
                BiometricManager
                        .from(this)
                        .canAuthenticate();

        if (result ==
                BiometricManager.BIOMETRIC_SUCCESS) {

            fingerprintButton.setVisibility(
                    View.VISIBLE
            );

        } else {

            fingerprintButton.setVisibility(
                    View.GONE
            );
        }
    }

    private void biometricUnlock() {

        Executor executor =
                ContextCompat.getMainExecutor(
                        this
                );

        BiometricPrompt prompt =
                new BiometricPrompt(
                        this,
                        executor,
                        new BiometricPrompt.AuthenticationCallback() {

                            @Override
                            public void onAuthenticationSucceeded(
                                    BiometricPrompt.AuthenticationResult result
                            ) {

                                unlockVault();
                            }

                            @Override
                            public void onAuthenticationError(
                                    int errorCode,
                                    CharSequence errString
                            ) {

                                Toast.makeText(
                                        MainActivity.this,
                                        errString,
                                        Toast.LENGTH_SHORT
                                ).show();
                            }
                        }
                );

        BiometricPrompt.PromptInfo info =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(
                                "Private Vault"
                        )
                        .setSubtitle(
                                "Unlock your private photos and videos"
                        )
                        .setNegativeButtonText(
                                "Use PIN"
                        )
                        .build();

        prompt.authenticate(info);
    }

    private String sha256(String value) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] bytes =
                    digest.digest(
                            value.getBytes("UTF-8")
                    );

            StringBuilder result =
                    new StringBuilder();

            for (byte b : bytes) {

                result.append(
                        String.format(
                                "%02x",
                                b
                        )
                );
            }

            return result.toString();

        } catch (Exception e) {

            return value;
        }
    }

    private void settings() {

        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        box.setPadding(
                40, 10, 40, 0
        );

        EditText oldPin =
                new EditText(this);

        oldPin.setHint(
                "Current PIN"
        );

        oldPin.setInputType(
                InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );

        box.addView(oldPin);

        EditText newPin =
                new EditText(this);

        newPin.setHint(
                "New PIN (4-8 digits)"
        );

        newPin.setInputType(
                InputType.TYPE_CLASS_NUMBER |
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );

        newPin.setMaxLength(8);

        box.addView(newPin);

        new AlertDialog.Builder(this)
                .setTitle(
                        "Change PIN"
                )
                .setView(box)
                .setPositiveButton(
                        "Save",
                        (dialog, which) -> {

                            String old =
                                    oldPin.getText()
                                            .toString();

                            String newP =
                                    newPin.getText()
                                            .toString();

                            String savedHash =
                                    getSharedPreferences(
                                            "secure",
                                            MODE_PRIVATE
                                    ).getString(
                                            "pin_hash",
                                            null
                                    );

                            String oldStoredPin =
                                    getSharedPreferences(
                                            "secure",
                                            MODE_PRIVATE
                                    ).getString(
                                            "pin",
                                            null
                                    );

                            boolean oldCorrect =
                                    (savedHash != null &&
                                            savedHash.equals(
                                                    sha256(old)
                                            ))
                                    ||
                                    (oldStoredPin != null &&
                                            oldStoredPin.equals(
                                                    old
                                            ));

                            if (!oldCorrect) {

                                Toast.makeText(
                                        this,
                                        "Current PIN is wrong",
                                        Toast.LENGTH_SHORT
                                ).show();

                                return;
                            }

                            if (newP.length() < 4 ||
                                    newP.length() > 8) {

                                Toast.makeText(
                                        this,
                                        "New PIN must be 4-8 digits",
                                        Toast.LENGTH_SHORT
                                ).show();

                                return;
                            }

                            getSharedPreferences(
                                    "secure",
                                    MODE_PRIVATE
                            ).edit()
                                    .putString(
                                            "pin_hash",
                                            sha256(newP)
                                    )
                                    .remove("pin")
                                    .apply();

                            Toast.makeText(
                                    this,
                                    "PIN changed",
                                    Toast.LENGTH_SHORT
                            ).show();

                            lockVault();
                        }
                )
                .setNegativeButton(
                        "Cancel",
                        null
                )
                .show();
    }

    private void copyToVault(Uri uri) {

        if (!unlocked) {
            return;
        }

        try {

            String mime =
                    getContentResolver()
                            .getType(uri);

            String extension;

            if (mime != null &&
                    mime.startsWith("video/")) {

                extension = ".mp4";

            } else {

                extension = ".jpg";
            }

            File output =
                    new File(
                            vault,
                            "media_" +
                            System.currentTimeMillis() +
                            "_" +
                            Math.abs(
                                    uri.hashCode()
                            ) +
                            extension
                    );

            try (
                    InputStream input =
                            getContentResolver()
                                    .openInputStream(uri);

                    OutputStream outputStream =
                            new FileOutputStream(
                                    output
                            )
            ) {

                byte[] buffer =
                        new byte[8192];

                int length;

                while (
                        (length =
                                input.read(buffer)) != -1
                ) {

                    outputStream.write(
                            buffer,
                            0,
                            length
                    );
                }
            }

            Toast.makeText(
                    this,
                    "Added to Private Vault",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Import failed: " +
                            e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void load() {

        if (!unlocked) {
            return;
        }

        media.clear();

        File[] files =
                vault.listFiles();

        if (files != null) {

            for (File file : files) {

                if (file.isFile()) {
                    media.add(file);
                }
            }
        }

        Collections.sort(
                media,
                (a, b) ->
                        Long.compare(
                                b.lastModified(),
                                a.lastModified()
                        )
        );

        adapter.notifyDataSetChanged();
    }

    private void actions(File file) {

        if (!unlocked) {
            return;
        }

        String[] options = {
                "Preview",
                "Restore to Gallery",
                "Delete permanently"
        };

        new AlertDialog.Builder(this)
                .setTitle("Vault File")
                .setItems(
                        options,
                        (dialog, which) -> {

                            if (which == 0) {

                                preview(file);

                            } else if (which == 1) {

                                restore(file);

                            } else {

                                deleteFile(file);
                            }
                        }
                )
                .show();
    }

    private void preview(File file) {

        /*
         * For now show a simple preview dialog for images.
         * Video preview can be added with a dedicated VideoView screen.
         */
        if (!file.getName().endsWith(".mp4")) {

            ImageView image =
                    new ImageView(this);

            image.setImageBitmap(
                    BitmapFactory.decodeFile(
                            file.getAbsolutePath()
                    )
            );

            image.setAdjustViewBounds(true);

            new AlertDialog.Builder(this)
                    .setView(image)
                    .setPositiveButton(
                            "Close",
                            null
                    )
                    .show();

        } else {

            Toast.makeText(
                    this,
                    "Video selected",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void deleteFile(File file) {

        new AlertDialog.Builder(this)
                .setTitle(
                        "Delete permanently?"
                )
                .setMessage(
                        "This file will be permanently deleted."
                )
                .setPositiveButton(
                        "Delete",
                        (dialog, which) -> {

                            if (file.delete()) {

                                load();

                                Toast.makeText(
                                        this,
                                        "Deleted",
                                        Toast.LENGTH_SHORT
                                ).show();

                            } else {

                                Toast.makeText(
                                        this,
                                        "Delete failed",
                                        Toast.LENGTH_SHORT
                                ).show();
                            }
                        }
                )
                .setNegativeButton(
                        "Cancel",
                        null
                )
                .show();
    }

    private void restore(File file) {

        if (!unlocked) {
            return;
        }

        try {

            boolean video =
                    file.getName()
                            .endsWith(".mp4");

            String mime =
                    video
                            ? "video/mp4"
                            : "image/jpeg";

            ContentValues values =
                    new ContentValues();

            values.put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "Restored_" +
                            System.currentTimeMillis() +
                            (video ? ".mp4" : ".jpg")
            );

            values.put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    mime
            );

            if (Build.VERSION.SDK_INT >= 29) {

                values.put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        video
                                ? "Movies/PrivateVault"
                                : "Pictures/PrivateVault"
                );
            }

            Uri collection =
                    video
                            ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            Uri destination =
                    getContentResolver()
                            .insert(
                                    collection,
                                    values
                            );

            if (destination == null) {

                throw new IOException(
                        "Could not create Gallery file"
                );
            }

            try (
                    InputStream input =
                            new FileInputStream(file);

                    OutputStream output =
                            getContentResolver()
                                    .openOutputStream(
                                            destination
                                    )
            ) {

                byte[] buffer =
                        new byte[8192];

                int length;

                while (
                        (length =
                                input.read(buffer)) != -1
                ) {

                    output.write(
                            buffer,
                            0,
                            length
                    );
                }
            }

            file.delete();

            load();

            Toast.makeText(
                    this,
                    "Restored to Gallery",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Restore failed: " +
                            e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    class MediaAdapter
            extends RecyclerView.Adapter<MediaAdapter.VH> {

        class VH
                extends RecyclerView.ViewHolder {

            ImageView image;
            TextView video;

            VH(View view) {

                super(view);

                image =
                        view.findViewById(
                                R.id.thumb
                        );

                video =
                        view.findViewById(
                                R.id.video
                        );
            }
        }

        @Override
        public VH onCreateViewHolder(
                ViewGroup parent,
                int viewType
        ) {

            View view =
                    getLayoutInflater()
                            .inflate(
                                    R.layout.item_media,
                                    parent,
                                    false
                            );

            return new VH(view);
        }

        @Override
        public void onBindViewHolder(
                VH holder,
                int position
        ) {

            File file =
                    media.get(position);

            if (file.getName()
                    .endsWith(".mp4")) {

                holder.image.setImageResource(
                        android.R.drawable
                                .ic_media_play
                );

                holder.video.setVisibility(
                        View.VISIBLE
                );

            } else {

                holder.image.setImageBitmap(
                        BitmapFactory.decodeFile(
                                file.getAbsolutePath()
                        )
                );

                holder.video.setVisibility(
                        View.GONE
                );
            }

            holder.itemView.setOnClickListener(
                    v -> actions(file)
            );
        }

        @Override
        public int getItemCount() {

            return media.size();
        }
    }
}
