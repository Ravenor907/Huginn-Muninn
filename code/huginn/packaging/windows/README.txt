HUGINN FOR WINDOWS

Run Huginn.exe. Nothing to install.

EXTRACT ALL FIRST
Huginn doesn't run from inside the zip. Double-clicking Huginn.exe while the
zip is open in Explorer copies only Huginn.exe somewhere temporary, without
its "lib" folder, and Windows then says a DLL is missing. Right-click the zip,
choose Extract All, and run Huginn.exe from the folder it makes.

Put that folder somewhere of your own that isn't synced to the cloud, like
C:\Users\<you>\Huginn. Desktop and Documents are often synced by OneDrive,
which would copy your chats and keys to the cloud.

FIRST RUN
Windows may say "Windows protected your PC", because Huginn.exe has no paid
code-signing certificate. Choose More info, then Run anyway.
On Windows 11 with Smart App Control turned on, Huginn.exe is blocked with no
"Run anyway" choice. Smart App Control is in Windows Security > App & browser
control > Smart App Control. On many versions of Windows, turning it back
on afterwards means resetting Windows, so decide with care.
To check that this download really came from the project (its signature and
build attestation), see:
https://github.com/Ravenor907/Huginn-Muninn/blob/main/code/shared/docs/releases.md

YOUR DATA
Everything Huginn keeps (your identity, contacts, chats and settings) is in
the "data" folder beside Huginn.exe. Huginn makes it the first time it runs,
readable by your Windows account and the computer's administrators only (not
on a USB, memory card or network drive). Settings > About shows where it is.
Don't open the same Huginn folder on two computers (a OneDrive or Dropbox
folder, say): Huginn refuses to, as two copies on one folder break chats.

UPDATING
Huginn updates itself and keeps the data folder.
To update by hand, close Huginn, then copy the new files over the old ones.
The zip has a "Huginn" folder at its top: copy what is INSIDE it (Huginn.exe,
lib, README.txt, LICENSE.txt) into your old Huginn folder. Extracting the zip
into the old folder makes Huginn\Huginn\Huginn.exe instead, a second copy
with no chats; Huginn says so if you open it.
Or move "data" into the new Huginn folder. Deleting the data folder deletes
your identity and chats (unless you have a backup).
