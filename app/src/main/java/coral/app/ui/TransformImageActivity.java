package coral.app.ui;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.loopj.android.http.Base64;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;
import com.squareup.picasso.Picasso;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import coral.app.Constants;
import coral.app.R;
import coral.app.adapters.StylesAdapter;
import coral.app.models.StyleModel;
import coral.app.util.RestClient;
import cz.msebera.android.httpclient.Header;

public class TransformImageActivity extends AppCompatActivity {
    private static final String LOG = "TransformActivity";
    private Uri imageURI = null;
    private ImageView imageView;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private FloatingActionButton portraitFab;
    private byte[] decodedBytes;
    private ProgressDialog progressDialog;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transform_image);

        recyclerView = findViewById(R.id.recyclerView);
        imageView = findViewById(R.id.imageView);
        portraitFab = findViewById(R.id.fab);
        progressBar = findViewById(R.id.styles_loading_progress_bar);
        progressDialog = new ProgressDialog(this);

        Intent intent = getIntent();
        imageURI = intent.getParcelableExtra(Constants.EXTRA_IMAGE);
        Picasso.get().load(imageURI).into(imageView);

        portraitFab.setOnClickListener((view) -> {
            uploadImage(Constants.PORTRAIT);
        });

        if (RestClient.isNetworkConnected(this)) {
            showStyles();
            portraitFab.setVisibility(View.VISIBLE);
            Snackbar.make(recyclerView, R.string.fetching_styles, Snackbar.LENGTH_SHORT).show();
        } else {
            portraitFab.setVisibility(View.INVISIBLE);
            Snackbar.make(recyclerView,
                    R.string.network_error_message, Snackbar.LENGTH_INDEFINITE).show();
        }

    }

    @Override
    protected void onResume() {
        IntentFilter i = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isInitialStickyBroadcast()) {
                    // Connectivity state has changed
                    if (RestClient.isNetworkConnected(TransformImageActivity.this)) {
                        Snackbar.make(recyclerView,
                                R.string.network_restored, Snackbar.LENGTH_SHORT).show();
                        showStyles();
                        recyclerView.setVisibility(View.VISIBLE);
                        portraitFab.setVisibility(View.VISIBLE);
                    } else {
                        Snackbar.make(recyclerView,
                                R.string.network_error_message, Snackbar.LENGTH_INDEFINITE).show();
                        progressBar.setVisibility(View.INVISIBLE);
                        recyclerView.setVisibility(View.INVISIBLE);
                        portraitFab.setVisibility(View.INVISIBLE);
                    }
                }
            }
        };
        registerReceiver(receiver, i);
        super.onResume();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(receiver);
        super.onPause();
    }

    private void showStyles() {
        ArrayList<StyleModel> styles = new ArrayList<>();

        RestClient.get("styles/get/", null, null, new JsonHttpResponseHandler() {
            @Override
            public void onStart() {
                super.onStart();
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONArray response) {
                for (int i = 0; i < response.length(); i++) {
                    try {
                        JSONObject styleObject = response.getJSONObject(i);
                        String name = styleObject.getString(Constants.NAME);
                        String thumbnail = styleObject.getString(Constants.THUMBNAIL);
                        styles.add(new StyleModel(name, thumbnail));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                recyclerView.setLayoutManager(new LinearLayoutManager(TransformImageActivity.this,
                        LinearLayoutManager.HORIZONTAL, false));
                recyclerView.setAdapter(new StylesAdapter(TransformImageActivity.this, styles,
                        style -> {
                            uploadImage(style);
                        }
                ));
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onFailure(int statusCode, Header[] headers,
                                  Throwable throwable, JSONObject errorResponse) {
                progressBar.setVisibility(View.INVISIBLE);
                Toast.makeText(TransformImageActivity.this,
                        R.string.error_fetching_styles, Toast.LENGTH_LONG).show();
                if (errorResponse != null)
                    Log.d(getLocalClassName(), errorResponse.toString());
                else Log.d(getLocalClassName(), "Null response" +
                        "");
            }

            @Override
            public void onFailure(int statusCode, Header[] headers,
                                  String responseString, Throwable throwable) {
                progressBar.setVisibility(View.INVISIBLE);
                Toast.makeText(TransformImageActivity.this,
                        R.string.error_fetching_styles, Toast.LENGTH_LONG).show();
                Log.d(getLocalClassName(), responseString);
            }
        });
    }

    private void uploadImage(String styleName) {
        RequestParams params = new RequestParams();

        try {
            File imageFile = new File(getRealPathFromURI(this.imageURI));
            System.out.print(getRealPathFromURI(this.imageURI));
            params.put(Constants.IMAGE, imageFile);
            params.put(Constants.STYLE, styleName);

            progressDialog.setCancelable(false);

            RestClient.setTimeOut(300000);

            RestClient.post("transform/", null, params,
                    new JsonHttpResponseHandler() {
                        @Override
                        public void onStart() {
                            super.onStart();
                            progressDialog.setMessage(getString(R.string.transforming));
                            progressDialog.show();
                        }

                        @Override
                        public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                            progressDialog.dismiss();
                            RestClient.setTimeOut(10000);
                            Log.d(LOG, response.toString());

                            try {
                                String base64String = response.getString(Constants.IMAGE_STRING);

                                byte[] decodedString = Base64.decode(base64String, Base64.DEFAULT);
                                Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0,
                                        decodedString.length);

                                imageView.setImageBitmap(decodedByte);
                                decodedBytes = decodedString;

                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onFailure(int statusCode, Header[] headers, Throwable throwable,
                                              JSONObject errorResponse) {
                            progressDialog.dismiss();
                            RestClient.setTimeOut(10000);
                            if (errorResponse != null)
                                Log.e(LOG, errorResponse.toString());
                            else Log.e(LOG, throwable.getMessage());
                            Toast.makeText(TransformImageActivity.this,
                                    R.string.error, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(int statusCode, Header[] headers, String responseString, Throwable throwable) {
                            progressDialog.dismiss();
                            RestClient.setTimeOut(10000);
                            if (responseString != null)
                                Log.e(LOG, responseString);
                            else Log.e(LOG, throwable.getMessage());
                            Toast.makeText(TransformImageActivity.this,
                                    R.string.failed_to_process, Toast.LENGTH_LONG).show();
                        }
                    });

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private String getRealPathFromURI(Uri uri) {
        String result;
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor == null) {
            result = uri.getPath();
        } else {
            cursor.moveToFirst();
            int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
            result = cursor.getString(idx);
            cursor.close();
        }
        return result;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_transform, menu);
        return true;
    }

    private void onSavePressed() {
        if (decodedBytes == null) {
            Toast.makeText(this, R.string.no_changes, Toast.LENGTH_SHORT).show();
        } else {
            new SavePhotoTask(decodedBytes).execute();
            decodedBytes = null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                break;
            case R.id.save:
                onSavePressed();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    class SavePhotoTask extends AsyncTask<Void, String, Void> {

        private byte[] imageBytes;

        SavePhotoTask(byte[] imageBytes) {
            this.imageBytes = imageBytes;
        }

        @Override
        protected void onPreExecute() {
            progressDialog.setCancelable(false);
            progressDialog.setMessage(getString(R.string.saving_image));
        }


        @Override
        protected Void doInBackground(Void... voids) {
            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-hhmmss");
            String filePath = "/" + getString(R.string.app_name)
                    + "/" + dateFormat.format(new Date()) + ".jpeg";

            File photo = new File(Environment.getExternalStorageDirectory(), filePath);

            if (photo.exists()) {
                photo.delete();
            }

            try {
                FileOutputStream fos = new FileOutputStream(photo.getPath());
                fos.write(imageBytes);
                fos.close();
            } catch (java.io.IOException e) {
                Log.e("SavePhotoTask", "IOException when saving image", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            progressDialog.dismiss();
            Toast.makeText(TransformImageActivity.this, "Saved", Toast.LENGTH_SHORT)
                    .show();
        }
    }

}
