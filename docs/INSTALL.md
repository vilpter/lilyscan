# Installing Lilyscan

This guide assumes you run a home server (a small Linux machine you reach over SSH) and have not
used Docker before. Every step says what to type and what you should see.

There are two parts:

- **[Part A: the web server](#part-a-the-web-server-docker)**, on your home server with Docker.
  You then use Lilyscan from any browser on your network (computer, tablet, phone). This is the
  main way to run it.
- **[Part B: the desktop app](#part-b-the-desktop-app-apt)**, optional, on an Ubuntu or Debian
  desktop, installed with `apt`. It can work on its own or connect to your server from Part A.

## Before you start

Check the server first:

| Need | How to check | |
|---|---|---|
| A 64-bit **x86** (Intel/AMD) machine | `uname -m` prints `x86_64` | ARM boards (Raspberry Pi, `aarch64`) are not supported yet |
| Linux, ideally **Ubuntu 22.04 or newer** or **Debian 12 or newer** | `cat /etc/os-release` | Other distributions work if they run Docker |
| **4 GB of memory**, 8 GB recommended | `free -h`, the "total" column | Reading a page needs about 3 GB for a moment |
| **5 GB of free disk**, plus room for your jobs | `df -h ~` | The image is about 700 MB; each job keeps its pages and results |
| Internet access | | To download Docker and Lilyscan |

You need a user that can run `sudo` (the first user you created when installing the server can).

## Part A: the web server (Docker)

### Step 1. Connect to the server

From your own computer, open a terminal (on Windows: PowerShell) and log in:

```bash
ssh yourname@your-server
```

Replace `yourname` with your user on the server and `your-server` with its name or IP address
(for example `192.168.1.20`). Every command below is typed **on the server**.

### Step 2. Install Docker

Skip this step if `docker compose version` already prints a version.

Lilyscan needs Docker Engine with the Compose plugin. Install it from Docker's own repository (the
version in Ubuntu's or Debian's archive is often too old). Copy each block and paste it whole.

**On Ubuntu:**

```bash
sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

**On Debian**, use the same block with `ubuntu` replaced by `debian` in both URLs (the
`download.docker.com/linux/...` parts).

Check that it works:

```bash
sudo docker run --rm hello-world
```

You should see `Hello from Docker!`. Docker starts by itself whenever the server boots.

**Optional: use Docker without `sudo`.** Add yourself to the `docker` group, then log out and back
in (`exit`, then `ssh` again):

```bash
sudo usermod -aG docker $USER
```

Only do this for your own account: members of the `docker` group can control the whole server.
The rest of this guide writes `docker`; if you skipped this, put `sudo` in front of every `docker`
command.

### Step 3. Get Lilyscan's Compose file

Lilyscan is described by one file, `docker-compose.yml`. Make a folder for it and download it:

```bash
mkdir -p ~/lilyscan
cd ~/lilyscan
curl -fsSLO https://github.com/vilpter/lilyscan/releases/latest/download/docker-compose.yml
ls
```

`ls` should show `docker-compose.yml`. This folder is where you run every Lilyscan command from
now on (`cd ~/lilyscan` first).

### Step 4. Start Lilyscan

```bash
docker compose up -d
```

The first time, Docker downloads the Lilyscan image (about 700 MB), so this takes a few minutes.
It ends with something like `Container lilyscan-lilyscan-1  Started`. Check it is running:

```bash
docker compose ps
```

The `STATUS` column should say `Up`. To watch its log (press `Ctrl+C` to stop watching; Lilyscan
keeps running):

```bash
docker compose logs -f
```

Wait for the line `Lilyscan server listening on port 8000`.

### Step 5. Open it in a browser

Find the server's address:

```bash
hostname -I
```

The first number, for example `192.168.1.20`, is its address on your network. On any computer,
tablet or phone in the same network, open:

```
http://192.168.1.20:8000/
```

You should see the Lilyscan home page with **New scan**, **Combine parts** and **Jobs**. Upload a
PDF or a photo of a page to try it. Reading takes about a minute per page; the page shows its
progress. The [README](../README.md#use) explains the review screen.

Lilyscan restarts by itself when the server reboots, until you stop it (below).

### Step 6. Keep it private

**Lilyscan has no login.** Anyone who can open the address can upload, see every job and download
the results. On a home network behind an ordinary router, only devices on that network can reach
it, which is usually what you want. Do **not** forward port 8000 on your router to the internet.

To use it from outside your home, choose one:

- **A VPN** such as [Tailscale](https://tailscale.com/kb/1031/install-linux) or WireGuard (the
  simplest: install it on the server and on your phone or laptop, then open
  `http://<the server's VPN address>:8000/`).
- **A reverse proxy with a password and HTTPS**, if you already run one. Make Lilyscan listen only
  on the server itself, so the proxy is the only way in. Create a file named `.env` next to
  `docker-compose.yml`:

  ```bash
  echo "LILYSCAN_BIND=127.0.0.1" >> .env
  docker compose up -d
  ```

  With [Caddy](https://caddyserver.com/docs/install), a site with a password looks like this
  (`caddy hash-password` makes the hash):

  ```
  lilyscan.example.com {
      basic_auth {
          yourname $2a$14$...the hash...
      }
      reverse_proxy 127.0.0.1:8000
  }
  ```

  With nginx, also allow large uploads: `client_max_body_size 200m;`.

A note on firewalls: Docker opens published ports itself, past `ufw` rules. So to keep Lilyscan off
some network, use `LILYSCAN_BIND` as above, not `ufw`. To limit it to one network card, set
`LILYSCAN_BIND` to that card's address (one of the numbers `hostname -I` prints).

### Settings

Settings go in the file `.env` next to `docker-compose.yml`, one per line. After changing it, run
`docker compose up -d` again.

| Line in `.env` | What it does |
|---|---|
| `LILYSCAN_PORT=8080` | Use port 8080 instead of 8000 (`http://<address>:8080/`) |
| `LILYSCAN_BIND=127.0.0.1` | Only reachable from the server itself (for a reverse proxy) |
| `LILYSCAN_OCR_LANGUAGES=eng+deu+ita` | Languages for reading lyrics and titles ([Tesseract codes](https://tesseract-ocr.github.io/tessdoc/Data-Files-in-different-versions.html), joined with `+`). English, Latin, German and French are built in; others are downloaded once, on the next start |
| `AUDIVERIS_MAX_HEAP=2G` | Memory for reading one page (default 3G). Lower it on a 4 GB server if jobs fail |
| `LILYSCAN_TAG=0.2.0` | Stay on a released version instead of the newest one |

To see the result of your `.env`: `docker compose config`.

### Updating

```bash
cd ~/lilyscan
docker compose pull
docker compose up -d
docker image prune -f
```

`pull` downloads the newest image, `up -d` restarts Lilyscan with it (your jobs are kept), and
`prune` deletes the old image to free disk. If you pinned a version with `LILYSCAN_TAG`, change it
first. To also pick up changes to `docker-compose.yml` itself, download it again (Step 3) before
pulling.

### Backing up your jobs

Your jobs live in a Docker volume named `lilyscan_lilyscan-data` (`docker volume ls` lists the
volumes; the first part is the folder name, `lilyscan`). To copy them into a file:

```bash
cd ~/lilyscan
docker compose stop
docker run --rm -v lilyscan_lilyscan-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/lilyscan-jobs-$(date +%F).tar.gz -C /data .
docker compose start
```

This makes `lilyscan-jobs-<date>.tar.gz` in `~/lilyscan`; copy it somewhere else. To restore one
(this replaces the current jobs; put your file's name in place of `lilyscan-jobs-2026-01-31.tar.gz`):

```bash
docker compose stop
docker run --rm -v lilyscan_lilyscan-data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -rf /data/* && tar xzf /backup/lilyscan-jobs-2026-01-31.tar.gz -C /data'
docker compose start
```

### Stopping and removing

| | |
|---|---|
| Stop it (until you start it again) | `docker compose stop`, later `docker compose start` |
| Remove the container, **keep** your jobs | `docker compose down` |
| Remove everything, **including your jobs** | `docker compose down -v`, then `docker image rm ghcr.io/vilpter/lilyscan` |

### When something goes wrong

Start with `docker compose ps` and `docker compose logs --tail 100`.

- **The page does not load.** Is it `Up` in `docker compose ps`? Are you using the server's
  address and the right port (`http://`, not `https://`)? Is `LILYSCAN_BIND` in `.env` limiting
  it? Can the other device reach the server at all (`ping` its address)?
- **`permission denied ... docker.sock`.** Put `sudo` in front, or do the optional part of Step 2
  and log in again.
- **`docker: 'compose' is not a docker command`.** The Compose plugin is missing: repeat the
  install line of Step 2. (The old `docker-compose` command with a dash is not the same.)
- **`unauthorized` or `denied` when pulling the image.** The image has not been made public yet;
  this is a one-time setting for the project's owner (docs/RELEASING.md).
- **`no matching manifest for linux/arm64`.** The server is ARM (Raspberry Pi and similar), which
  is not supported yet.
- **A job fails with "out of memory" or the server becomes slow.** Lower `AUDIVERIS_MAX_HEAP` to
  `2G`, upload fewer pages per job, or add memory.
- **A job fails on a long book with a timeout.** Raise `AUDIVERIS_TIMEOUT_PER_PAGE_S` (seconds per
  page, default 240) in `.env`.
- **Lyrics in another language come out wrong.** Add the language to `LILYSCAN_OCR_LANGUAGES`
  (Settings). The download at startup needs internet access; the log says if it failed.
- **Lilyscan was stopped while a job ran** (a reboot, an update). That job shows as failed with
  "re-run it"; open it and use **Re-run…**.

## Part B: the desktop app (apt)

The desktop app is the same Lilyscan in a window. It needs a computer with a screen and either:

- **Ubuntu 24.04 or newer** (or Linux Mint 22, Pop!_OS 24.04 and others based on it), or
- **Debian 13 or newer**,

on 64-bit Intel/AMD. Everything it needs comes with it (its own Java, Audiveris, LilyPond and the
OCR models), so it is a large download (about 230 MB).

### Install

Open a terminal on that computer and add Lilyscan's apt repository. This tells `apt` where to
find Lilyscan and which key it is signed with:

```bash
sudo install -d -m 0755 /etc/apt/keyrings
sudo curl -fsSLo /etc/apt/keyrings/lilyscan.gpg \
  https://github.com/vilpter/lilyscan/releases/download/apt/lilyscan-archive-keyring.gpg
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/lilyscan.gpg] https://github.com/vilpter/lilyscan/releases/download/apt ./" \
  | sudo tee /etc/apt/sources.list.d/lilyscan.list
```

Then install it:

```bash
sudo apt update
sudo apt install lilyscan
```

`apt update` should list a line ending in `apt ./ InRelease`. Start Lilyscan from the applications
menu, or with `/opt/lilyscan/bin/Lilyscan`.

### Use it with your server, or on its own

- **On its own**: it starts its own copy of the Lilyscan engine and keeps the jobs in
  `~/.local/share/lilyscan`. Nothing else to set up.
- **With the server from Part A**: in the app choose **Connection → Lilyscan server…** and enter
  `http://192.168.1.20:8000` (your server's address). The app works on the server's jobs, so
  phones and computers all see the same ones. It remembers the choice.

### Updates

Lilyscan is updated with the rest of your system:

```bash
sudo apt update
sudo apt upgrade
```

(The Software Updater does the same.)

### Remove

```bash
sudo apt remove lilyscan
```

Your jobs stay in `~/.local/share/lilyscan`; delete that folder to remove them too. To also remove
the repository: `sudo rm /etc/apt/sources.list.d/lilyscan.list /etc/apt/keyrings/lilyscan.gpg`.

### If something goes wrong

- **`apt update` says the signature is invalid, or `NO_PUBKEY`.** The repository's key changed or
  the key file is missing: download it again with the `sudo curl ...` line above, then
  `sudo apt update`.
- **`lilyscan : Depends: libasound2t64 but it is not installable`.** The system is older than
  Ubuntu 24.04 or Debian 13. Use the web server (Part A) instead, which runs anywhere Docker does.
- **Without the repository**: download `lilyscan_<version>_amd64.deb` from the
  [Releases page](https://github.com/vilpter/lilyscan/releases) and install it with
  `sudo apt install ./lilyscan_<version>_amd64.deb` (the `./` matters). This gets no automatic
  updates.
- **Fedora or openSUSE**: the Releases page also has a `.rpm`:
  `sudo dnf install ./lilyscan-<version>-1.x86_64.rpm`.

## Windows and macOS

There is no desktop installer for these yet. Run the web server with
[Docker Desktop](https://docs.docker.com/desktop/) instead: install it, start it, then in a
terminal (PowerShell on Windows) follow Part A from Step 3. On Windows, download the Compose file
with:

```powershell
mkdir $HOME\lilyscan; cd $HOME\lilyscan
Invoke-WebRequest https://github.com/vilpter/lilyscan/releases/latest/download/docker-compose.yml -OutFile docker-compose.yml
```

Then open `http://localhost:8000/`. On a Mac with Apple silicon (M1 and later), Docker Desktop runs
the image through emulation, which works but is slower.

## Building from source

To build the image or the packages yourself, see "Install" and "Develop" in the
[README](../README.md).
