## MS-AV-02-Segurança e Observabilidade

- Implementar um mecanismo de login em um sistema genérico seguindo o padrão RESTFul e Spring Boot como tecnologia de suporte com os seguintes *end-points* e requisitos
    - POST /login: efetua login de um usuário passando no corpo da requisição o JSON {username: "", senha: ""} e retornar 200 (usuário e senha válidos) ou 401 (usuário e senha inválidos)
        - Esse *end-ponit* deverá ser acessado publicamente e a consistência de usuário e senha será feita pela própria tabela de usuários (vide tabela **usuarios** no SQL abaixo)
    - PUT /trocarsenha/{userid}: efetua a troca de senha do *userid* passando como parâmetros o JSON {atual: "", nova: ""} (senha atual e a nova senha - consistir com a base de dados) deve ser acessado publicamente
    - POST /: cadastra um novo usuário recebendo no corpo da requisição o JSON {username: "", senha: ""} e somente pode ser executado por usuários com *role* **ROLE_MANUTENCAO**
        - Utilizar a api [JdbcUserDetailsManager](https://github.com/esensato/ms-2026-02#definindo-usu%C3%A1rios-em-banco-de-dados-jdbc) para implementar a segurança com roles
    - PUT /unlock/{userid}: desbloqueia um usuário passando o *userid* do usuário a ser desbloqueado - este endpoint somente pode ser acessado por um administrador, com a *role* **ROLE_ADMIN**
        - Utilizar a api [JdbcUserDetailsManager](https://github.com/esensato/ms-2026-02#definindo-usu%C3%A1rios-em-banco-de-dados-jdbc) para implementar a segurança com roles
    - GET / : retorna todos os usuários cadastrados em formato JSON mas somente pode ser acessado por um usuário com a *role* **ROLE_MANUTENCAO**
    - Criar indicadores que registrem a quantidade de logins válidos, a quantidade de inválidos e o total de usuários bloqueados
    - Criar um dashboard no grafana para exibir a quantidade de logins válidos, a quantidade de inválidos e o total de usuários bloqueados
    - Caso o usuário falhe mais de uma vez no login ele deve ser bloqueado (vide tabela login_attempts abaixo) 
    - Documentar os *end-points* utilizando OPENAPI e gerar a interface swagger
    - Utilizar o script SQL abaixo para criar a tabela de controle de quantidade de logins válidos, a quantidade de inválidos e o total de usuários bloqueados
```sql
CREATE TABLE IF NOT EXISTS login_attempts (
    username VARCHAR(100) PRIMARY KEY,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    last_failed_at TIMESTAMP NULL,
    locked_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
);

CREATE TABLE IF NOT EXISTS usuarios (
    username VARCHAR(100) PRIMARY KEY,
    senha VARCHAR(10)
);
```
- Para entrega:
    - Efetuar upload de todo o projeto em alguma repo git e compartilhar o link (deve ser repositório público!!!)
    - No repositório deve também constar um print screen do dashboard criado no grafana
    - Exportar o dashboard criado seguindo essas instruções [aqui](https://grafana.com/docs/grafana/latest/visualizations/dashboards/share-dashboards-panels/?utm_source=chatgpt.com#export-a-dashboard-as-code)