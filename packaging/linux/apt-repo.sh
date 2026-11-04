#!/bin/sh
# Builds a signed flat apt repository holding one .deb (the release workflow runs this, then uploads
# the result to the `apt` GitHub Release; docs/RELEASING.md).
#
#   APT_SIGNING_KEY="$(cat private-key.asc)" [APT_SIGNING_PASSPHRASE=...] \
#     packaging/linux/apt-repo.sh dist/lilyscan_0.2.0_amd64.deb OUT_DIR
#
# OUT_DIR receives the .deb, Packages, Packages.gz, Release, InRelease, Release.gpg and the public
# key in two forms (lilyscan-archive-keyring.gpg for /etc/apt/keyrings, and .asc). Users add:
#   deb [arch=amd64 signed-by=/etc/apt/keyrings/lilyscan.gpg] <base URL> ./
# A flat repository needs no dists/ tree, so it can live on a release (whose assets have no
# folders, and may be larger than the 100 MB a repository file may be).
set -eu

deb="${1:?usage: apt-repo.sh PACKAGE.deb OUT_DIR}"
out="${2:?usage: apt-repo.sh PACKAGE.deb OUT_DIR}"
[ -f "$deb" ] || { echo "no such package: $deb" >&2; exit 66; }
if [ -z "${APT_SIGNING_KEY:-}" ]; then
  echo "APT_SIGNING_KEY is not set: the repository must be signed (see docs/RELEASING.md)" >&2
  exit 78
fi
for tool in dpkg-scanpackages apt-ftparchive gpg; do
  command -v "$tool" >/dev/null || { echo "missing $tool (apt install dpkg-dev apt-utils gnupg)" >&2; exit 69; }
done

mkdir -p "$out"
cp "$deb" "$out/"
cd "$out"

# A private keyring, so nothing touches the caller's own GnuPG setup.
# Under /tmp: gpg-agent's socket path must stay short (a deep $TMPDIR breaks it).
GNUPGHOME="$(mktemp -d /tmp/lilyscan-gpg.XXXXXX)"
export GNUPGHOME
trap 'gpgconf --kill gpg-agent 2>/dev/null; rm -rf "$GNUPGHOME"' EXIT
printf '%s\n' "$APT_SIGNING_KEY" | gpg --batch --quiet --import
fpr="$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')"
[ -n "$fpr" ] || { echo "APT_SIGNING_KEY holds no secret key" >&2; exit 65; }

# Filenames relative to the repository's base URL, without the "./" dpkg-scanpackages puts first.
dpkg-scanpackages --multiversion . /dev/null 2>/dev/null | sed 's|^Filename: \./|Filename: |' > Packages
gzip -9nkf Packages
apt-ftparchive \
  -o APT::FTPArchive::Release::Origin=Lilyscan \
  -o APT::FTPArchive::Release::Label=Lilyscan \
  -o APT::FTPArchive::Release::Architectures=amd64 \
  -o APT::FTPArchive::Release::Description="Lilyscan: sheet music in, editable LilyPond out" \
  release . > Release.new
mv Release.new Release

sign() {
  if [ -n "${APT_SIGNING_PASSPHRASE:-}" ]; then
    gpg --batch --yes --pinentry-mode loopback --passphrase "$APT_SIGNING_PASSPHRASE" --local-user "$fpr" "$@"
  else
    gpg --batch --yes --local-user "$fpr" "$@"
  fi
}
sign --clearsign --digest-algo SHA512 -o InRelease Release
sign --armor --detach-sign --digest-algo SHA512 -o Release.gpg Release
gpg --batch --export "$fpr" > lilyscan-archive-keyring.gpg
gpg --batch --armor --export "$fpr" > lilyscan-archive-keyring.asc

echo "signed by $fpr:"
ls -la
