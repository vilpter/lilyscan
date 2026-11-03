# The rpm spec for lilyscan: jpackage's template (its placeholders are filled in by jpackage),
# with the desktop menu entry's install and removal allowed to fail, so that the package installs
# and uninstalls on a system without a desktop menu.
Summary: APPLICATION_SUMMARY
Name: APPLICATION_PACKAGE
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: APPLICATION_LICENSE_TYPE
Vendor: APPLICATION_VENDOR

Prefix: %{dirname:APPLICATION_DIRECTORY}

Provides: APPLICATION_PACKAGE

Autoprov: 0
Autoreq: 0
Requires: PACKAGE_DEFAULT_DEPENDENCIES PACKAGE_CUSTOM_DEPENDENCIES

%define __jar_repack %{nil}
%define _build_id_links none

%define package_filelist %{_builddir}/%{name}.files
%define app_filelist %{_builddir}/%{name}.app.files
%define filesystem_filelist %{_builddir}/%{name}.filesystem.files

%define default_filesystem / /opt /usr /usr/bin /usr/lib /usr/local /usr/local/bin /usr/local/lib

%description
APPLICATION_DESCRIPTION

%global __os_install_post %{nil}

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}APPLICATION_DIRECTORY
cp -r %{_sourcedir}APPLICATION_DIRECTORY/* %{buildroot}APPLICATION_DIRECTORY
(cd %{buildroot} && find . -type d -print) | sed -e 's/^\.//' -e '/^$/d' | sort > %{app_filelist}
{ rpm -ql filesystem 2>/dev/null || printf "%%s\n" %{default_filesystem}; } | sort > %{filesystem_filelist}
comm -23 %{app_filelist} %{filesystem_filelist} > %{package_filelist}
sed -i -e 's/.*/%dir "&"/' %{package_filelist}
(cd %{buildroot} && find . -not -type d) | sed -e 's/^\.//' -e 's/.*/"&"/' >> %{package_filelist}

%files -f %{package_filelist}

%post
xdg-desktop-menu install APPLICATION_DIRECTORY/lib/lilyscan-Lilyscan.desktop \
  || echo "lilyscan: no desktop menu to add Lilyscan to; run APPLICATION_DIRECTORY/bin/Lilyscan" >&2
:

%preun
# On removal only (not on upgrade), and only if no other package owns the menu entry.
if [ "$1" = 0 ] && [ -e APPLICATION_DIRECTORY/lib/lilyscan-Lilyscan.desktop ] \
    && [ "$(rpm -q --whatprovides APPLICATION_DIRECTORY/lib/lilyscan-Lilyscan.desktop | wc -l)" = 1 ]; then
  xdg-desktop-menu uninstall APPLICATION_DIRECTORY/lib/lilyscan-Lilyscan.desktop || :
fi
:

%clean
