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
          amorFatiJvmOpts = "-Xmx8G -XX:+UseG1GC";
          amorFatiJava = pkgs.writeShellScriptBin "amor-fati-java" ''
            set -euo pipefail

            java_opts="''${AMOR_FATI_JAVA_OPTS:-}"
            if [[ -z "$java_opts" ]]; then
              java_opts="${amorFatiJvmOpts}"
            fi

            # AMOR_FATI_JAVA_OPTS is intentionally split into JVM arguments.
            exec ${pkgs.jdk21}/bin/java $java_opts "$@"
          '';
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              amorFatiJava
              bashInteractive
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
              # flake.lock currently pins z3 4.15.4 from nixos-25.11. Changes
              # can affect Stainless/ledger verification behavior.
              z3
            ];

            JAVA_HOME = pkgs.jdk21.home;
            AMOR_FATI_JAVA_OPTS = amorFatiJvmOpts;
            SBT_OPTS = amorFatiJvmOpts;
            SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
            GIT_SSL_CAINFO = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
          };
        });
    };
}
