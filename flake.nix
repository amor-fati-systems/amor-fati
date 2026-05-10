{
  description = "Development shell for Amor Fati";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
  };

  outputs = { nixpkgs, ... }:
    let
      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-darwin"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              bashInteractive
              cacert
              coreutils
              curl
              findutils
              gawk
              git
              gnugrep
              gnused
              gnutar
              gzip
              jdk21
              python3
              sbt
              unzip
              which
              z3
            ];

            JAVA_HOME = pkgs.jdk21.home;
            SBT_OPTS = "-Xmx4G -XX:+UseG1GC";
            SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
            GIT_SSL_CAINFO = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";

            shellHook = ''
              export PATH="$JAVA_HOME/bin:$PATH"
              echo "Amor Fati dev shell: JDK 21, sbt, Python 3, Z3"
              echo "SBT_OPTS=$SBT_OPTS"
            '';
          };
        });
    };
}
