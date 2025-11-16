# Connexió

Per connectar amb el servidor remot cal configurar l'arxiu **"config.env"** amb:

- El nom d'usuari 
- El *path* a l'arxiu *id_rsa*
- El port al que funciona el servidor

Per connectar amb el servidor remot es pot fer servir l'script:

```bash
# Desde el terminal local
./proxmocConnect.sh
# Obre una connexió "super" al terminal remot
```

El servidor remot ha de tenir els següents paquets instal·lats:

```bash
# Al servidor remot afegeix els paquets necessaris
sudo apt update
sudo apt install -y openjdk-21-jre procps grep gawk util-linux net-tools
```

El servidor remot rep peticions pel port *80*, per seguretat és millor redirigir-les a un altre port (el del nostre servidor), per fer-ho:

```bash
# Al terminal local
./proxmoxRedirect80.sh
```
