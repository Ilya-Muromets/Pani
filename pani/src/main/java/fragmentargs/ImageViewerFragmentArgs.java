package fragmentargs;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.NavArgs;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.HashMap;

public class ImageViewerFragmentArgs implements NavArgs {
  private final HashMap arguments = new HashMap();

  private ImageViewerFragmentArgs() {
  }

  @SuppressWarnings("unchecked")
  private ImageViewerFragmentArgs(HashMap argumentsMap) {
    this.arguments.putAll(argumentsMap);
  }

  @NonNull
  @SuppressWarnings("unchecked")
  public static ImageViewerFragmentArgs fromBundle(@NonNull Bundle bundle) {
    ImageViewerFragmentArgs __result = new ImageViewerFragmentArgs();
    bundle.setClassLoader(ImageViewerFragmentArgs.class.getClassLoader());
    if (bundle.containsKey("file_path")) {
      String filePath;
      filePath = bundle.getString("file_path");
      if (filePath == null) {
        throw new IllegalArgumentException("Argument \"file_path\" is marked as non-null but was passed a null value.");
      }
      __result.arguments.put("file_path", filePath);
    } else {
      throw new IllegalArgumentException("Required argument \"file_path\" is missing and does not have an android:defaultValue");
    }
    if (bundle.containsKey("orientation")) {
      int orientation;
      orientation = bundle.getInt("orientation");
      __result.arguments.put("orientation", orientation);
    } else {
      __result.arguments.put("orientation", 0);
    }
    if (bundle.containsKey("depth")) {
      boolean depth;
      depth = bundle.getBoolean("depth");
      __result.arguments.put("depth", depth);
    } else {
      __result.arguments.put("depth", false);
    }
    return __result;
  }

  @NonNull
  @SuppressWarnings("unchecked")
  public static ImageViewerFragmentArgs fromSavedStateHandle(
      @NonNull SavedStateHandle savedStateHandle) {
    ImageViewerFragmentArgs __result = new ImageViewerFragmentArgs();
    if (savedStateHandle.contains("file_path")) {
      String filePath;
      filePath = savedStateHandle.get("file_path");
      if (filePath == null) {
        throw new IllegalArgumentException("Argument \"file_path\" is marked as non-null but was passed a null value.");
      }
      __result.arguments.put("file_path", filePath);
    } else {
      throw new IllegalArgumentException("Required argument \"file_path\" is missing and does not have an android:defaultValue");
    }
    if (savedStateHandle.contains("orientation")) {
      int orientation;
      orientation = savedStateHandle.get("orientation");
      __result.arguments.put("orientation", orientation);
    } else {
      __result.arguments.put("orientation", 0);
    }
    if (savedStateHandle.contains("depth")) {
      boolean depth;
      depth = savedStateHandle.get("depth");
      __result.arguments.put("depth", depth);
    } else {
      __result.arguments.put("depth", false);
    }
    return __result;
  }

  @SuppressWarnings("unchecked")
  @NonNull
  public String getFilePath() {
    return (String) arguments.get("file_path");
  }

  @SuppressWarnings("unchecked")
  public int getOrientation() {
    return (int) arguments.get("orientation");
  }

  @SuppressWarnings("unchecked")
  public boolean getDepth() {
    return (boolean) arguments.get("depth");
  }

  @SuppressWarnings("unchecked")
  @NonNull
  public Bundle toBundle() {
    Bundle __result = new Bundle();
    if (arguments.containsKey("file_path")) {
      String filePath = (String) arguments.get("file_path");
      __result.putString("file_path", filePath);
    }
    if (arguments.containsKey("orientation")) {
      int orientation = (int) arguments.get("orientation");
      __result.putInt("orientation", orientation);
    } else {
      __result.putInt("orientation", 0);
    }
    if (arguments.containsKey("depth")) {
      boolean depth = (boolean) arguments.get("depth");
      __result.putBoolean("depth", depth);
    } else {
      __result.putBoolean("depth", false);
    }
    return __result;
  }

  @SuppressWarnings("unchecked")
  @NonNull
  public SavedStateHandle toSavedStateHandle() {
    SavedStateHandle __result = new SavedStateHandle();
    if (arguments.containsKey("file_path")) {
      String filePath = (String) arguments.get("file_path");
      __result.set("file_path", filePath);
    }
    if (arguments.containsKey("orientation")) {
      int orientation = (int) arguments.get("orientation");
      __result.set("orientation", orientation);
    } else {
      __result.set("orientation", 0);
    }
    if (arguments.containsKey("depth")) {
      boolean depth = (boolean) arguments.get("depth");
      __result.set("depth", depth);
    } else {
      __result.set("depth", false);
    }
    return __result;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
        return true;
    }
    if (object == null || getClass() != object.getClass()) {
        return false;
    }
    ImageViewerFragmentArgs that = (ImageViewerFragmentArgs) object;
    if (arguments.containsKey("file_path") != that.arguments.containsKey("file_path")) {
      return false;
    }
    if (getFilePath() != null ? !getFilePath().equals(that.getFilePath()) : that.getFilePath() != null) {
      return false;
    }
    if (arguments.containsKey("orientation") != that.arguments.containsKey("orientation")) {
      return false;
    }
    if (getOrientation() != that.getOrientation()) {
      return false;
    }
    if (arguments.containsKey("depth") != that.arguments.containsKey("depth")) {
      return false;
    }
    if (getDepth() != that.getDepth()) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int result = 1;
    result = 31 * result + (getFilePath() != null ? getFilePath().hashCode() : 0);
    result = 31 * result + getOrientation();
    result = 31 * result + (getDepth() ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return "ImageViewerFragmentArgs{"
        + "filePath=" + getFilePath()
        + ", orientation=" + getOrientation()
        + ", depth=" + getDepth()
        + "}";
  }

  public static final class Builder {
    private final HashMap arguments = new HashMap();

    @SuppressWarnings("unchecked")
    public Builder(@NonNull ImageViewerFragmentArgs original) {
      this.arguments.putAll(original.arguments);
    }

    @SuppressWarnings("unchecked")
    public Builder(@NonNull String filePath) {
      if (filePath == null) {
        throw new IllegalArgumentException("Argument \"file_path\" is marked as non-null but was passed a null value.");
      }
      this.arguments.put("file_path", filePath);
    }

    @NonNull
    public ImageViewerFragmentArgs build() {
      ImageViewerFragmentArgs result = new ImageViewerFragmentArgs(arguments);
      return result;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public Builder setFilePath(@NonNull String filePath) {
      if (filePath == null) {
        throw new IllegalArgumentException("Argument \"file_path\" is marked as non-null but was passed a null value.");
      }
      this.arguments.put("file_path", filePath);
      return this;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public Builder setOrientation(int orientation) {
      this.arguments.put("orientation", orientation);
      return this;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public Builder setDepth(boolean depth) {
      this.arguments.put("depth", depth);
      return this;
    }

    @SuppressWarnings({"unchecked","GetterOnBuilder"})
    @NonNull
    public String getFilePath() {
      return (String) arguments.get("file_path");
    }

    @SuppressWarnings({"unchecked","GetterOnBuilder"})
    public int getOrientation() {
      return (int) arguments.get("orientation");
    }

    @SuppressWarnings({"unchecked","GetterOnBuilder"})
    public boolean getDepth() {
      return (boolean) arguments.get("depth");
    }
  }
}
