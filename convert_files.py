import numpy as np
import matplotlib.pyplot as plt
import os
from os import path
from glob import glob
from natsort import natsorted
from tqdm import tqdm
import re
import ast
import rawpy
import cv2
import argparse

def parse_metadata_string(metadata_string):
    keys = re.findall(r'<KEY>android.(.*?)<ENDKEY>', metadata_string)
    values = re.findall(r'<VALUE>(.*?)<ENDVALUE>', metadata_string)
    
    metadata_dict = {}

    for key, value in zip(keys, values):
        # Convert simple values to the appropriate type
        if value == 'true':
            value = True
        elif value == 'false':
            value = False
        elif re.fullmatch(r'[0-9]+', value):
            value = int(value)
        elif re.fullmatch(r'[0-9E]*\.[0-9E]+', value):
            value = float(value)
        metadata_dict[key] = value

    return metadata_dict

def write_mp4(frames, video_name='test.mp4', fps=24.0):
    if len(frames[0].shape) == 3:
        height, width, layers = frames[0].shape
    else:
        height, width = frames[0].shape
        layers = 1
    
    frames = frames - frames.min()
    frames = (frames/frames.max() * 255).astype(np.uint8)

    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    video = cv2.VideoWriter(video_name, fourcc, fps, (width,height))

    if layers == 4: # RGBA -> BGR
        for frame in tqdm(frames):
            video.write(frame[:,:,[2,1,0]])
    elif layers == 3: # RGB -> BGR
        for frame in tqdm(frames):
            video.write(frame[:,:,[2,1,0]])
    elif layers == 1: # grayscale
        for frame in tqdm(frames):
            video.write(frame[:,:,None].repeat(3,2))
    else:
        raise Exception("Unsupported array size.")

    cv2.destroyAllWindows()
    video.release()

def process_motion(npz_file, motion_path):
    # Load motion data
    with open(motion_path, mode='rb') as file:
            motion = str(file.read())

    motion = motion.split("<ENDACC>")
    acceleration = motion[:-1]
    quaternion = motion[-1].split("<ENDROT>")

    # Read acceleration values

    acceleration_timestamps = []
    acceleration_values = []

    for acc in acceleration:
        acc = re.sub("[^-0-9.,E]", "", acc).split(',')

        acceleration_timestamps.append(int(acc[0]))
        acceleration_values.append([float(x) for x in acc[1:]])

    # Android acceleration, in portrait mode, follows the following convention:
    # +x: right along short side of screen, towards power button
    # +y: up along long side of screen, towards front facing camera
    # +z: out of screen, towards your face

    acceleration_timestamps = np.array(acceleration_timestamps)/1e9
    acceleration_values = np.array(acceleration_values)

    quaternion_timestamps = []
    quaternion_values = []

    for rot in quaternion[:-1]:
        rot = re.sub("[^-0-9.,E]", "", rot).split(',')

        quaternion_timestamps.append(int(rot[0]))
        quaternion_values.append([float(x) for x in rot[1:]])

    quaternion_timestamps = np.array(quaternion_timestamps)/1e9
    quaternion_values = np.array(quaternion_values)

    quaternion_timestamps, unique_quaternion_indices = np.unique(quaternion_timestamps, return_index=True)
    quaternion_values = quaternion_values[unique_quaternion_indices]

    # resample acceleration values to match quaternion timestamps
    interpolated_acceleration_values = np.empty((len(quaternion_timestamps), 3))

    for i in range(3): # x, y, z
        interpolated_acceleration_values[:, i] = np.interp(quaternion_timestamps, acceleration_timestamps, acceleration_values[:, i])


    motion = {'timestamp': quaternion_timestamps,
              'quaternion': quaternion_values,
              'acceleration': interpolated_acceleration_values
            }
    
    npz_file['motion'] = motion

def process_characteristics(npz_file, characteristics_path):

    with open(characteristics_path, mode='rb') as file:
        characteristics_string= str(file.read())

    characteristics_dict = parse_metadata_string(characteristics_string)

    return

def process_metadata(npz_file, metadata_paths):

    for metadata_path in metadata_paths:
        with open(metadata_path, mode='rb') as file:
            metadata_string = str(file.read())

        metadata_dict = parse_metadata_string(metadata_string)

        fx,fy,cx,cy,s = list(metadata_dict['lens.intrinsicCalibration'].split(','))
        intrinsics = np.array([[fx, 0,  0],
                            [s,  fy, 0],
                            [cx, cy, 1]], dtype=np.float32)

        frame_count = int(metadata_path.split("_")[-1].strip(".bin"))

        timestamp = metadata_dict['sensor.timestamp']/1e9 # convert to seconds
        ISO = metadata_dict['sensor.sensitivity']
        exposure_time = metadata_dict['sensor.exposureTime']/1e9 # convert to seconds
        aperture = metadata_dict['lens.aperture']
        # BGGR bayer black-level
        blacklevel = np.array(list(metadata_dict['sensor.dynamicBlackLevel'].split(',')), np.float32)
        whitelevel = metadata_dict['sensor.dynamicWhiteLevel']
        focal_length = metadata_dict['lens.focalLength']
        focus_distance = metadata_dict['lens.focusDistance']

        # Extract per-channel shading maps
        shade_map = metadata_dict['statistics.lensShadingCorrectionMap']

        shade_map = shade_map.replace("R:","|")
        shade_map = shade_map.replace("G_even:","|")
        shade_map = shade_map.replace("G_odd:","|")
        shade_map = shade_map.replace("B:","|")
        shade_map = re.sub('[^0-9.,\[\]\|]', '', shade_map)

        R,G1,G2,B = shade_map.split("|")[1:]
        R = np.array(ast.literal_eval(R)) # match portrait rotation
        G1 = np.array(ast.literal_eval(G1))
        G2 = np.array(ast.literal_eval(G2))
        B = np.array(ast.literal_eval(B))   

        shade_map = np.stack([R,G1,G2,B], axis=-1)

        lens_distortion = metadata_dict['lens.distortion']
        lens_distortion = lens_distortion = np.array([float(f) for f in lens_distortion.split(',')])


        raw_frame = {'android': metadata_dict, # original metadata
                    'frame_count': frame_count,
                    'timestamp': timestamp,
                    'ISO': ISO,
                    'exposure_time': exposure_time,
                    'aperture': aperture,
                    'blacklevel': blacklevel,
                    'whitelevel': whitelevel,
                    'focal_length': focal_length,
                    'focus_distance': focus_distance,
                    'intrinsics': intrinsics,
                    'shade_map': shade_map,
                    'lens_distortion': lens_distortion}

        npz_file[f'raw_{frame_count}'] = raw_frame
        
    npz_file['num_raw_frames'] = frame_count + 1

def process_characteristics(npz_file, characteristics_path):

    with open(characteristics_path, mode='rb') as file:
        characteristics_string= str(file.read())

    characteristics_dict = parse_metadata_string(characteristics_string)

    # 0: RGGB, 1: GRBG, 2: GBRG, 3: BGGR
    color_filter_arrangement = characteristics_dict['sensor.info.colorFilterArrangement']
    pose_reference = characteristics_dict['lens.poseReference']
    pose_rotation = characteristics_dict['lens.poseRotation']
    pose_rotation = np.array([float(f) for f in pose_rotation.split(',')])
    pose_translation = characteristics_dict['lens.poseTranslation']
    pose_translation = np.array([float(f) for f in pose_translation.split(',')])
    aperture = characteristics_dict['lens.info.availableApertures']
    focal_length = characteristics_dict['lens.info.availableFocalLengths']
    minimum_focus_distance = characteristics_dict['lens.info.minimumFocusDistance']
    hyperfocal_distance = characteristics_dict['lens.info.hyperfocalDistance']

    characteristics = {'android' : characteristics_dict,
                    'color_filter_arrangement' : color_filter_arrangement,
                    'pose_reference' : pose_reference,
                    'pose_rotation' : pose_rotation,
                    'pose_translation' : pose_translation,
                    'aperture' : aperture,
                    'focal_length' : focal_length,
                    'minimum_focus_distance' : minimum_focus_distance,
                    'hyperfocal_distance' : hyperfocal_distance}

    npz_file["characteristics"] = characteristics


def process_raw(npz_file, raw_paths):

    for raw_path in raw_paths:
        frame_count = int(raw_path.split("_")[-1].strip(".dng"))

        raw = rawpy.imread(raw_path).raw_image
        height, width = raw.shape

        if f'raw_{frame_count}' not in npz_file.keys():
            npz_file[f'raw_{frame_count}'] = {}
        
        npz_file[f'raw_{frame_count}']['raw'] = raw
        npz_file[f'raw_{frame_count}']['height'] = height
        npz_file[f'raw_{frame_count}']['width'] = width
    
# Sort raw and metadata files by timestamp, remove dropped frames or metadata
def sort_and_filter_files(npz_file):
    # all the raw images or metadata we received
    raw_keys = [key for key in npz_file.keys() if 'raw_' in key and 'num_raw_frames' not in key]

    raw_keys_matched = []
    for raw_key in raw_keys:
        # we received both raw and metadata for this frame
        if 'raw' in npz_file[raw_key].keys() and 'timestamp' in npz_file[raw_key].keys():
            raw_keys_matched.append(raw_key)
    
    timestamps = np.array([npz_file[raw_key]['timestamp'] for raw_key in raw_keys_matched])
    sorted_indices = np.argsort(timestamps)
    raw_keys_matched = np.array(raw_keys_matched)[sorted_indices] # sort by timestamp

    # make new dict with sorted raw and metadata
    npz_file_sorted = {}
    for frame_count, raw_key in enumerate(raw_keys_matched):
        npz_file_sorted[f'raw_{frame_count}'] = npz_file[raw_key]
        npz_file_sorted[f'raw_{frame_count}']['frame_count'] = frame_count
    
    npz_file_sorted['num_raw_frames'] = len(raw_keys_matched)
    npz_file_sorted['motion'] = npz_file['motion']
    npz_file_sorted['characteristics'] = npz_file['characteristics']

    return npz_file_sorted

# save preview grayscale video of RAW data
def save_preview_video(npz_file, save_path):
    frames = np.array([np.rot90(npz_file[f'raw_{i}']['raw'], 3) for i in range(npz_file['num_raw_frames'])])
    frames = frames - np.percentile(frames[0], 1) # remove 1% darkest pixels
    frames = frames / np.percentile(frames[0], 99) # burn out 1% brightest pixels
    frames = frames.clip(0,1)
    frames = frames[:,::2,::2] + frames[:,1::2,::2] + frames[:,::2,1::2] + frames[:,1::2,1::2] # average bggr channels
    frames = np.log1p(10*frames) # log transform for better visualization
    write_mp4(frames, save_path, fps=15.0)

def has_subfolders(folder):
    for _, dirnames, _ in os.walk(folder):
        if len(dirnames) > 0:
            return True
    return False

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('-d', default=None, type=str, required=True, help='Data directory')
    args = parser.parse_args()

    if has_subfolders(args.d):
        # procesing multiple bundles
        bundle_paths = natsorted(glob(path.join(args.d, "*/")))
        # filter out processed bundles, this will code will fail if it is the year 3000 or later
        bundle_paths = [path.normpath(bundle_path) for bundle_path in bundle_paths if "processed_2" not in bundle_path]
    else:
        # processing single bundle
        bundle_paths = [path.normpath(args.d)]

    for bundle_path in bundle_paths:
        try:
            motion_path = path.join(bundle_path, "MOTION.bin")
            characteristics_path = path.join(bundle_path, "CHARACTERISTICS.bin")
            raw_paths = natsorted(glob(path.join(bundle_path, "IMG*.dng")))
            metadata_paths = natsorted(glob(path.join(bundle_path, "IMG*.bin")))

            npz_file = {}
            npz_file["bundle_name"] = path.basename(bundle_path)

            print(f"Processing: {bundle_path}")
            process_motion(npz_file, motion_path)
            process_characteristics(npz_file, characteristics_path)
            process_metadata(npz_file, metadata_paths)
            process_raw(npz_file, raw_paths)
            npz_file = sort_and_filter_files(npz_file)

            # write to npz file
            if has_subfolders(args.d): # add processed_ prefix to parent folder
                parent, child = path.dirname(bundle_path), path.basename(bundle_path)
                parent = path.join(path.dirname(parent), "processed_" + path.basename(parent))
                save_path = path.join(parent, child)
            else:
                save_path = path.join(path.dirname(bundle_path), "processed_" + path.basename(bundle_path))

            os.makedirs(save_path, exist_ok=True)
            print(f"Saving to: {save_path}")
            np.savez_compressed(path.join(save_path, "frame_bundle.npz"), **npz_file)
            save_preview_video(npz_file, path.join(save_path, "preview.mp4"))
            print("Done.")

        except Exception as e:
            print(f"Error processing {bundle_path}: {e}")

if __name__ == '__main__':
    main()