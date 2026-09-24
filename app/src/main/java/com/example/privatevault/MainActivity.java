package com.example.privatevault;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    File vault;
    RecyclerView grid;
    ArrayList<File> media = new ArrayList<>();
    MediaAdapter adapter;
    ActivityResultLauncher<String[]> picker;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        vault = new File(getFilesDir(),"vault");
        if(!vault.exists()) vault.mkdirs();

        grid=findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this,3));
        adapter=new MediaAdapter();
        grid.setAdapter(adapter);

        picker=registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris->{
            if(uris!=null) for(Uri u:uris) copyToVault(u);
            load();
        });

        findViewById(R.id.add).setOnClickListener(v ->
            picker.launch(new String[]{"image/*","video/*"}));
        findViewById(R.id.settings).setOnClickListener(v -> settings());

        authenticate();
    }

    void authenticate(){
        String pin=getSharedPreferences("secure",0).getString("pin",null);
        if(pin==null){ load(); return; }

        Executor ex=ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt=new BiometricPrompt(this,ex,new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){ load(); }
            @Override public void onAuthenticationError(int c,CharSequence e){ showPin(); }
        });
        BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Private Vault").setSubtitle("Unlock your vault")
            .setNegativeButtonText("Use PIN").build();
        if(BiometricManager.from(this).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
            prompt.authenticate(info);
        else showPin();
    }

    void showPin(){
        EditText e=new EditText(this); e.setInputType(2); e.setHint("PIN");
        new AlertDialog.Builder(this).setTitle("Enter PIN").setView(e)
            .setCancelable(false).setPositiveButton("Unlock",(d,w)->{
                String p=getSharedPreferences("secure",0).getString("pin","");
                if(p.equals(e.getText().toString())) load();
                else { Toast.makeText(this,"Wrong PIN",Toast.LENGTH_SHORT).show(); showPin(); }
            }).show();
    }

    void settings(){
        EditText e=new EditText(this); e.setInputType(2); e.setHint("New 4-8 digit PIN");
        new AlertDialog.Builder(this).setTitle("Set PIN").setView(e)
            .setPositiveButton("Save",(d,w)->{
                String p=e.getText().toString();
                if(p.length()>=4 && p.length()<=8){
                    getSharedPreferences("secure",0).edit().putString("pin",p).apply();
                    Toast.makeText(this,"PIN saved",Toast.LENGTH_SHORT).show();
                } else Toast.makeText(this,"PIN must be 4-8 digits",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Cancel",null).show();
    }

    void copyToVault(Uri u){
        try{
            String mime=getContentResolver().getType(u);
            String ext=(mime!=null && mime.startsWith("video/"))?".mp4":".jpg";
            File out=new File(vault,"media_"+System.currentTimeMillis()+ext);
            try(InputStream in=getContentResolver().openInputStream(u);
                OutputStream o=new FileOutputStream(out)){
                byte[] b=new byte[8192]; int n;
                while((n=in.read(b))!=-1)o.write(b,0,n);
            }
            Toast.makeText(this,"Hidden in Vault",Toast.LENGTH_SHORT).show();
        }catch(Exception e){ Toast.makeText(this,"Import failed",Toast.LENGTH_SHORT).show(); }
    }

    void load(){
        media.clear();
        File[] f=vault.listFiles();
        if(f!=null) media.addAll(Arrays.asList(f));
        Collections.sort(media,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        adapter.notifyDataSetChanged();
    }

    void actions(File f){
        String[] a={"Preview","Restore to Gallery","Delete permanently"};
        new AlertDialog.Builder(this).setTitle("Vault file").setItems(a,(d,w)->{
            if(w==1) restore(f);
            if(w==2) new AlertDialog.Builder(this).setTitle("Delete?").setMessage("This cannot be undone.")
                .setPositiveButton("Delete",(x,y)->{f.delete();load();}).setNegativeButton("Cancel",null).show();
        }).show();
    }

    void restore(File f){
        try{
            String mime=f.getName().endsWith(".mp4")?"video/mp4":"image/jpeg";
            ContentValues v=new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,mime.startsWith("video")?"Movies/PrivateVault":"Pictures/PrivateVault");
            Uri uri=getContentResolver().insert(mime.startsWith("video")?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
            try(OutputStream o=getContentResolver().openOutputStream(uri); InputStream in=new FileInputStream(f)){
                byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1)o.write(b,0,n);
            }
            f.delete(); load(); Toast.makeText(this,"Restored to Gallery",Toast.LENGTH_SHORT).show();
        }catch(Exception e){Toast.makeText(this,"Restore failed",Toast.LENGTH_SHORT).show();}
    }

    class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.VH>{
        class VH extends RecyclerView.ViewHolder{
            ImageView img; TextView video;
            VH(View v){super(v);img=v.findViewById(R.id.thumb);video=v.findViewById(R.id.video);}
        }
        public VH onCreateViewHolder(android.view.ViewGroup p,int t){
            return new VH(getLayoutInflater().inflate(R.layout.item_media,p,false));
        }
        public void onBindViewHolder(VH h,int pos){
            File f=media.get(pos);
            if(f.getName().endsWith(".mp4")){
                h.img.setImageResource(android.R.drawable.ic_media_play); h.video.setVisibility(View.VISIBLE);
            }else{
                h.img.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath())); h.video.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v->actions(f));
        }
        public int getItemCount(){return media.size();}
    }
}