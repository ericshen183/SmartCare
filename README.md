# SmartCare
SmartCare is intended to be a paired application with consumer-grade wearables for caretakers and family members to track and monitor elderly
family members. The application currently only supports smartwatches that utilize the Moyoung protocol such as iConnect and the TK37 or devices that
use the DaFit companion application as well as android devices.

The application utilises in-built sensors to effectively monitor, track a wearer's Heartrate, movement, location tracking and fall detection.
The application currently has limited features, which include:
1.  Heart rate monitoring
2.  Proximity Alert
3.  Reminder system (The reminder system is under development, syncing the time with the application is a small issue
   we intend to fix)

Firstly, we need to clone the repository. Start by opening the command prompt in any directory and running the following command:
```
git clone https://github.com/ericshen183/SmartCare.git
```

<img width="1136" height="392" alt="image" src="https://github.com/user-attachments/assets/3226abd3-33bd-4fd1-a114-4f80d73525bd" />


Ensure you have git installed and add this command to your environmental variables.
Next, install Android Studio to build and test the application.\
<img width="1000" height="800" alt="image" src="https://github.com/user-attachments/assets/63297f8d-c91b-49c5-9077-61790090211c" />
From here, you can start the program and sign in to your Google account.
Upon entering the application, select the directory you have just cloned\
<img width="497" height="764" alt="Screenshot 2026-05-21 095840" src="https://github.com/user-attachments/assets/c123bf6b-3710-47ce-890d-2292714015a2" />

Let Android Studio initialize the directory and you should be met with this\
<img width="1221" height="877" alt="image" src="https://github.com/user-attachments/assets/3e9d960c-9f02-4b00-94c1-0265ab503080" />
From this moment on most of the initial setup has been completed.

## Connecting your device to Android Studio
Now, we need to connect our device to the Android Studio program in order to test the application on our devices.
Connecting your device to the Android Studio program can be through two ways.
1. adb connection
2. Wifi pairing
   
### Enabling Developer Mode
To be able to establish a connection through any of the methods mentioned above, developer mode must be enabled, first go into settings and scroll down
until you see this:
<img width="850" height="566" alt="image" src="https://github.com/user-attachments/assets/86e3e34b-79e1-4cd1-a7ec-f07936e029ac" />
Click on about phone and navigate to software information
<img width="702" height="562" alt="image" src="https://github.com/user-attachments/assets/4ea204cc-da2e-4ffe-9d22-35835055d053" />
Then click on build number 7 times to start developer mode:
<img width="253" height="512" alt="image" src="https://github.com/user-attachments/assets/920f85ff-6536-4f1e-92eb-9fc095e818ba" />
Then search and enable USB debugging for adb connection or Wireless Debugging if both devices are connected on the network.

### ADB connection
To establish an adb connection you first need to download adb platform tools through this link
https://dl.google.com/android/repository/platform-tools-latest-windows.zip
From here you can unzip the folder in any directory of your own choosing and copy its path into environmental variables:
Copy the path
<img width="763" height="576" alt="Screenshot 2026-05-21 111719" src="https://github.com/user-attachments/assets/a4177978-e45b-4620-b7eb-62a17613bc5b" />
Open environmental variables\
<img width="936" height="439" alt="Screenshot 2026-05-21 112019" src="https://github.com/user-attachments/assets/7f37fdd7-660e-4d69-b75a-3a63f6f9ed1c" />
Name the variable by any name you want and paste the file path you had copied and remove the quotes.\
<img width="1211" height="561" alt="Screenshot 2026-05-21 112545" src="https://github.com/user-attachments/assets/9a422f64-cd18-4223-a4e2-8a7c65d882e4" />
After that close the window and apply the changes.
Now you can go back to Android studio and using your device's specified charging cable (Preferably a usb cable) you can connect the two devices. To test if it has been correctly attached, go into Android studio's terminal or open a new command prompt window and use the command ``` adb devices```
Or if you have issues creating the environmental variables simply open the terminal by right-clicking in the folder platform-tools
and copy and run the command.
You should see a device listed in this manner:\
<img width="296" height="48" alt="image" src="https://github.com/user-attachments/assets/282c2aff-df88-4644-877b-09a2c6c7db01" />


### Wifi Pairing
Ensure both the mobile device you want to connect and your pc/ computer are on the same network. Then in Android Studio, click on the no devices segment:\
<img width="466" height="141" alt="Screenshot 2026-05-21 123254" src="https://github.com/user-attachments/assets/6dba402b-7de7-415d-b3f6-f9010a8bd865" />
And select pair devices through wifi.\
<img width="763" height="927" alt="image" src="https://github.com/user-attachments/assets/53cd5f68-fcb3-4738-8cda-81c29c39ea19" />
From here you can go back to developer options, turn on wireless debugging and select to either connect through the QR code or Pairing code.

### Connected Device
Now we can return to Android studio and we can see the device we connected listed here. We can now go ahead and start debugging which would start installing the application on your mobile device.\
<img width="442" height="52" alt="image" src="https://github.com/user-attachments/assets/9210b30d-2243-48a6-b45c-3fa0867d46ad" />
You can now test the application on your mobile device.
